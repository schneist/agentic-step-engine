package ase.runner

import ase.core.Canonical
import cats.data.NonEmptyList
import cats.syntax.all.*
import io.circe.{ACursor, Json, JsonObject, parser}

import java.util.Locale

/** Eine Messserie als Datensatz (D#77): Schlüssel, Bedingungen, Stand und die Kennzahlen jedes Laufs. series.sh führt
  * die Läufe aus; `ase series begin` hält Schlüssel und Bedingungen vor dem ersten Lauf fest, `ase series end` baut
  * daraus und aus den Laufverzeichnissen den Datensatz, `ase compare` vergleicht zwei. Gespeichert werden nur die
  * Läufe; Erfolgsquote, Intervall und Spannen entstehen beim Lesen, damit es keine zweite Quelle für sie gibt.
  */
object Series:
  val Version = 1

  /** Verlangte Läufe, wenn die Eval keine series.json hat (playbook/evals.md). */
  val DefaultRequired = 10

  enum SourceKind:
    case Task, Flow

  /** Was gleich sein muss, damit zwei Serien vergleichbar sind. source ist nur der Dateiname, die Fassung steht in
    * sourceSha; fixtureSha ist ein Tree-Hash (TreeHash), envelopeSha der SHA-256 des kanonischen JSON der Hülle.
    */
  final case class Key(
      kind: SourceKind,
      source: String,
      sourceSha: String,
      envelopeSha: String,
      fixtureSha: String,
      checkSha: String,
      provider: String,
      model: String,
      effort: String
  ):
    def fields: List[(String, String)] = List(
      "kind" -> kind.toString,
      "source" -> source,
      "sourceSha" -> sourceSha,
      "envelopeSha" -> envelopeSha,
      "fixtureSha" -> fixtureSha,
      "checkSha" -> checkSha,
      "provider" -> provider,
      "model" -> model,
      "effort" -> effort
    )

    def hash: String = Canonical.sha256Hex(Canonical.print(encodeKey(this)))

  /** Wie die Serie lief. planned: angekündigte Läufe; required: verlangte (series.json der Eval); reference: Fingerprint
    * des Referenzflows. Parallel ist eine Bedingung, kein Schlüssel: Es ändert die Dauer, nicht das Verhalten.
    */
  final case class Conditions(aseCommit: String, dirty: List[String], planned: Int, required: Int, parallel: Boolean, reference: Option[String])

  /** Was `ase series begin` vor dem ersten Lauf schreibt. fixtureDir bleibt lokal: `ase series end` hasht das Fixture
    * dort erneut; in den Datensatz geht der Pfad nicht.
    */
  final case class Input(eval: String, date: String, key: Key, conditions: Conditions, fixtureDir: String)

  /** Ein Lauf: Ausgang, Prüfung und Code-Hinweise von series.sh (run.json), der Rest aus summary.json oder, wenn nichts
    * lief, aus plan-summary.json (dann ohne Dauer, sourceSha und workspaceSha). workspaceSha: Tree-Hash des
    * Arbeitsverzeichnisses vor dem Lauf (D#78); series.sh kopiert das Fixture dorthin, also muss er fixtureSha sein.
    */
  final case class Run(
      outcome: String,
      check: Boolean,
      codeHints: Int,
      steps: Long,
      planCalls: Int,
      fillCalls: Int,
      holeRounds: Int,
      gate1: Int,
      gate2: Int,
      unusable: Int,
      asked: Boolean,
      tokens: Long,
      seconds: Option[Double],
      planFingerprint: Option[String],
      sourceSha: Option[String],
      workspaceSha: Option[String] = None
  )

  /** „Was nicht zählt“ aus playbook/evals.md, geprüft statt nur gesagt. */
  enum NotCounting:
    case DirtyTree(files: List[String])
    case TooFewRuns(n: Int, required: Int)
    case RunsNotAsPlanned(found: Int, planned: Int)
    case FixtureChanged(expected: String, found: String)
    case StartNotFixture(runs: Int)
    case MixedKeys(sourceShas: List[String])

    def render: String = this match
      case DirtyTree(fs)           => s"Arbeitsbaum geändert (${fs.take(3).mkString(", ")}${if fs.size > 3 then s" und ${fs.size - 3} weitere" else ""})"
      case TooFewRuns(n, r)        => s"$n Läufe, verlangt $r"
      case RunsNotAsPlanned(f, p)  => s"$f Läufe gefunden, $p angekündigt"
      case FixtureChanged(e, f)    => s"Fixture während der Serie geändert (${e.take(8)} → ${f.take(8)})"
      case StartNotFixture(k)      => s"$k ${if k == 1 then "Lauf begann" else "Läufe begannen"} nicht mit dem Fixture"
      case MixedKeys(shas)         => s"Läufe aus anderen Fassungen (${shas.map(_.take(8)).mkString(", ")})"

  enum Standing:
    case Counts
    case DoesNotCount(reasons: NonEmptyList[NotCounting])

  final case class Record(eval: String, date: String, key: Key, conditions: Conditions, standing: Standing, runs: Vector[Run], schema: Int = Version)

  /** Der Datensatz einer Serie; was nicht zählt, wird markiert, nicht weggelassen. fixtureNow: Tree-Hash des Fixtures am
    * Ende der Serie.
    */
  def build(input: Input, fixtureNow: String, runs: Vector[Run]): Record =
    val c = input.conditions
    val foreign = runs.flatMap(_.sourceSha).filter(_ != input.key.sourceSha).distinct.toList
    val otherStart = runs.count(_.workspaceSha.exists(_ != input.key.fixtureSha))
    val reasons = List(
      Option.when(c.dirty.nonEmpty)(NotCounting.DirtyTree(c.dirty)),
      Option.when(runs.size < c.required)(NotCounting.TooFewRuns(runs.size, c.required)),
      Option.when(runs.size != c.planned)(NotCounting.RunsNotAsPlanned(runs.size, c.planned)),
      Option.when(fixtureNow != input.key.fixtureSha)(NotCounting.FixtureChanged(input.key.fixtureSha, fixtureNow)),
      Option.when(otherStart > 0)(NotCounting.StartNotFixture(otherStart)),
      Option.when(foreign.nonEmpty)(NotCounting.MixedKeys(foreign))
    ).flatten
    Record(input.eval, input.date, input.key, c, NonEmptyList.fromList(reasons).fold(Standing.Counts)(Standing.DoesNotCount(_)), runs)

  // ---- Kennzahlen

  final case class Interval(low: Double, high: Double)
  final case class Spread(median: Double, min: Double, max: Double)

  /** Wilson-Intervall für k Erfolge in n Läufen, 95 %. Ohne Läufe das ganze Intervall. */
  def wilson(k: Int, n: Int): Interval =
    if n == 0 then Interval(0, 1)
    else
      val z = 1.959964
      val p = k.toDouble / n
      val d = 1 + z * z / n
      val center = (p + z * z / (2.0 * n)) / d
      val half = z * math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
      Interval(math.max(0, center - half), math.min(1, center + half))

  def spread(xs: Vector[Double]): Option[Spread] =
    Option.when(xs.nonEmpty) {
      val s = xs.sorted
      val median = if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2
      Spread(median, s.head, s.last)
    }

  final case class Figures(
      n: Int,
      success: Int,
      interval: Interval,
      gate1: Int,
      gate2: Int,
      asked: Int,
      tokensMean: Double,
      tokens: Option[Spread],
      seconds: Option[Spread],
      codeHint: Int,
      referenceMatches: Option[Int]
  )

  /** Erfolg heißt: check.sh bestanden. Ask zählt Läufe, die gefragt haben oder mit ask endeten (wie bisher series.sh). */
  def figures(r: Record): Figures =
    val n = r.runs.size
    val ok = r.runs.count(_.check)
    Figures(
      n,
      ok,
      wilson(ok, n),
      r.runs.map(_.gate1).sum,
      r.runs.map(_.gate2).sum,
      r.runs.count(x => x.asked || x.outcome == "ask"),
      if n == 0 then 0 else r.runs.map(_.tokens).sum.toDouble / n,
      spread(r.runs.map(_.tokens.toDouble)),
      spread(r.runs.flatMap(_.seconds)),
      r.runs.count(_.codeHints > 0),
      r.conditions.reference.map(ref => r.runs.count(_.planFingerprint.contains(ref)))
    )

  // ---- Vergleich

  enum Verdict:
    case Noise, Better, Worse
    case NotCompared(reason: String)

    def render: String = this match
      case Noise          => "Rauschen"
      case Better         => "besser"
      case Worse          => "schlechter"
      case NotCompared(r) => s"nicht verglichen: $r"

  enum Side:
    case Baseline, Candidate

    def label: String = this match
      case Baseline  => "die Basis"
      case Candidate => "der Kandidat"

  enum NotComparable:
    case SchemaMismatch(baseline: Int, candidate: Int)
    case KeyMismatch(fields: NonEmptyList[(String, String, String)])
    case DoesNotCount(side: Side, reasons: NonEmptyList[NotCounting])

    def render: String = this match
      case SchemaMismatch(a, b) => s"nicht vergleichbar: Schema $a gegen $b"
      case KeyMismatch(fs) =>
        // nur Hashes gekürzt; Namen wie das Modell bleiben ganz
        def show(k: String, v: String) = if k.endsWith("Sha") && v.length > 16 then v.take(12) + "…" else v
        ("nicht vergleichbar, der Schlüssel unterscheidet sich:" :: fs.toList.map((k, a, b) => s"  $k: ${show(k, a)} gegen ${show(k, b)}")).mkString("\n")
      case DoesNotCount(side, rs) => s"nicht vergleichbar: ${side.label} zählt nicht: ${rs.toList.map(_.render).mkString("; ")}"

  /** Ein Vergleich zweier Serien. Entsteht nur aus `Comparison.of`, wie ApprovedTerm nur aus Approval.approve (D#11). */
  final case class Comparison private (baseline: Record, candidate: Record, success: Verdict, tokens: Verdict, seconds: Verdict)

  object Comparison:
    /** Nur bei gleicher Schemaversion, gleichem Schlüssel und wenn beide zählen. Besser oder schlechter nur, wenn sich
      * die Intervalle (Erfolg) oder Spannen (Tokens, Dauer) nicht überlappen; die Dauer nur bei gleicher Parallelität.
      */
    def of(baseline: Record, candidate: Record): Either[NotComparable, Comparison] =
      val differing = baseline.key.fields.zip(candidate.key.fields).collect { case ((k, a), (_, b)) if a != b => (k, a, b) }
      for
        _ <- Either.cond(baseline.schema == candidate.schema, (), NotComparable.SchemaMismatch(baseline.schema, candidate.schema))
        _ <- NonEmptyList.fromList(differing).map(NotComparable.KeyMismatch(_)).toLeft(())
        _ <- counts(Side.Baseline, baseline)
        _ <- counts(Side.Candidate, candidate)
      yield
        val (b, c) = (figures(baseline), figures(candidate))
        val seconds =
          if baseline.conditions.parallel != candidate.conditions.parallel then Verdict.NotCompared("verschiedene Parallelität")
          else cost(b.seconds, c.seconds, "Dauer")
        Comparison(baseline, candidate, rate(b.interval, c.interval), cost(b.tokens, c.tokens, "Tokens"), seconds)

    private def counts(side: Side, r: Record): Either[NotComparable, Unit] = r.standing match
      case Standing.Counts               => Right(())
      case Standing.DoesNotCount(reasons) => Left(NotComparable.DoesNotCount(side, reasons))

    private def rate(b: Interval, c: Interval): Verdict =
      if c.low > b.high then Verdict.Better else if c.high < b.low then Verdict.Worse else Verdict.Noise

    /** Weniger ist besser. */
    private def cost(b: Option[Spread], c: Option[Spread], what: String): Verdict = (b, c) match
      case (Some(b), Some(c)) => if c.max < b.min then Verdict.Better else if c.min > b.max then Verdict.Worse else Verdict.Noise
      case _                  => Verdict.NotCompared(s"keine $what")

  // ---- Darstellung

  private def fmt(pattern: String, x: Double): String = pattern.formatLocal(Locale.ROOT, x)
  private def tokens(x: Double): String = if x >= 1000 then fmt("%.1fk", x / 1000) else fmt("%.0f", x)
  private def secs(x: Double): String = fmt("%.0f s", x)
  private def pct(x: Double): String = fmt("%.0f", x * 100)

  private def spreadText(s: Spread, show: Double => String): String =
    if show(s.min) == show(s.max) then show(s.min) else s"Median ${show(s.median)}, ${show(s.min)} bis ${show(s.max)}"

  private def successText(f: Figures): String = s"${f.success}/${f.n} (95 %: ${pct(f.interval.low)} bis ${pct(f.interval.high)} %)"

  private def standingText(s: Standing): String = s match
    case Standing.Counts               => "zählt"
    case Standing.DoesNotCount(reasons) => s"zählt nicht: ${reasons.toList.map(_.render).mkString("; ")}"

  /** Die M#-Zeile für findings.md (playbook/evals.md); von Hand kommt nur die Anmerkung dazu. */
  def line(id: String, r: Record): String =
    val f = figures(r)
    val source = r.key.kind match
      case SourceKind.Task => s"Aufgabe ${r.key.source}"
      case SourceKind.Flow => s"Flow ${r.key.source}"
    List(
      s"- $id ${r.date} Eval ${r.eval}, $source",
      s"Modell ${r.key.model}, effort ${r.key.effort}, über ${r.key.provider}",
      s"Commit ${r.conditions.aseCommit}",
      s"n=${f.n}${if r.conditions.parallel then ", parallel" else ""}",
      s"Erfolg ${successText(f)}",
      s"Tor1 ${f.gate1}",
      s"Tor2 ${f.gate2}",
      s"Ask ${f.asked}",
      s"Tokens ø ${tokens(f.tokensMean)}" + f.tokens.filter(s => tokens(s.min) != tokens(s.max)).fold("")(s => s" (${spreadText(s, tokens)})"),
      s"Dauer ${f.seconds.fold("–")(spreadText(_, secs))}",
      s"Referenz-Fingerprint ${f.referenceMatches.fold("–")(k => s"$k/${f.n}")}",
      s"Code-Hinweis ${f.codeHint}/${f.n}",
      s"Schlüssel ${r.key.hash.take(8)}",
      standingText(r.standing)
    ).mkString(" · ")

  def renderComparison(baselineId: String, candidateId: String, cmp: Comparison): String =
    val (b, c) = (figures(cmp.baseline), figures(cmp.candidate))
    def runs(r: Record, f: Figures) = s"${f.n}${if r.conditions.parallel then ", parallel" else ""}"
    def opt(s: Option[Spread], show: Double => String) = s.fold("–")(spreadText(_, show))
    def row(label: String, text: String) = f"$label%-11s $text"
    List(
      row("Vergleich", s"$baselineId → $candidateId · Schlüssel ${cmp.baseline.key.hash.take(8)}"),
      row("Commit", s"${cmp.baseline.conditions.aseCommit} → ${cmp.candidate.conditions.aseCommit}"),
      row("n", s"${runs(cmp.baseline, b)} → ${runs(cmp.candidate, c)}"),
      row("Erfolg", s"${successText(b)} → ${successText(c)} · ${cmp.success.render}"),
      row("Tokens", s"${opt(b.tokens, tokens)} → ${opt(c.tokens, tokens)} · ${cmp.tokens.render}"),
      row("Dauer", s"${opt(b.seconds, secs)} → ${opt(c.seconds, secs)} · ${cmp.seconds.render}"),
      row("Tor1 Tor2", s"${b.gate1} ${b.gate2} → ${c.gate1} ${c.gate2}"),
      row("Ask", s"${b.asked} → ${c.asked}")
    ).mkString("\n")

  /** M#18 aus measurements/M18.json; andere Dateinamen tragen keine Nummer. */
  def idOf(fileName: String): Option[String] =
    "^M(\\d+)\\.json$".r.findFirstMatchIn(fileName).map(m => s"M#${m.group(1)}")

  // ---- JSON

  def encodeKey(k: Key): Json = Json.obj(k.fields.map((name, v) => name -> Json.fromString(v))*)

  private def encodeConditions(c: Conditions): Json = Json.obj(
    "aseCommit" -> Json.fromString(c.aseCommit),
    "dirty" -> Json.arr(c.dirty.map(Json.fromString)*),
    "planned" -> Json.fromInt(c.planned),
    "required" -> Json.fromInt(c.required),
    "parallel" -> Json.fromBoolean(c.parallel),
    "reference" -> c.reference.fold(Json.Null)(Json.fromString)
  ).dropNullValues

  private def encodeReason(r: NotCounting): Json = r match
    case NotCounting.DirtyTree(fs)          => Json.obj("kind" -> Json.fromString("DirtyTree"), "files" -> Json.arr(fs.map(Json.fromString)*))
    case NotCounting.TooFewRuns(n, req)     => Json.obj("kind" -> Json.fromString("TooFewRuns"), "n" -> Json.fromInt(n), "required" -> Json.fromInt(req))
    case NotCounting.RunsNotAsPlanned(f, p) => Json.obj("kind" -> Json.fromString("RunsNotAsPlanned"), "found" -> Json.fromInt(f), "planned" -> Json.fromInt(p))
    case NotCounting.FixtureChanged(e, f)   => Json.obj("kind" -> Json.fromString("FixtureChanged"), "expected" -> Json.fromString(e), "found" -> Json.fromString(f))
    case NotCounting.StartNotFixture(k)     => Json.obj("kind" -> Json.fromString("StartNotFixture"), "runs" -> Json.fromInt(k))
    case NotCounting.MixedKeys(shas)        => Json.obj("kind" -> Json.fromString("MixedKeys"), "sourceShas" -> Json.arr(shas.map(Json.fromString)*))

  private def encodeStanding(s: Standing): Json = s match
    case Standing.Counts               => Json.obj("counts" -> Json.True)
    case Standing.DoesNotCount(reasons) => Json.obj("counts" -> Json.False, "reasons" -> Json.arr(reasons.toList.map(encodeReason)*))

  private def encodeRun(r: Run): Json = Json.obj(
    "outcome" -> Json.fromString(r.outcome),
    "check" -> Json.fromBoolean(r.check),
    "codeHints" -> Json.fromInt(r.codeHints),
    "steps" -> Json.fromLong(r.steps),
    "planCalls" -> Json.fromInt(r.planCalls),
    "fillCalls" -> Json.fromInt(r.fillCalls),
    "holeRounds" -> Json.fromInt(r.holeRounds),
    "gate1Rejections" -> Json.fromInt(r.gate1),
    "gate2Rejections" -> Json.fromInt(r.gate2),
    "unusableAnswers" -> Json.fromInt(r.unusable),
    "asked" -> Json.fromBoolean(r.asked),
    "tokens" -> Json.fromLong(r.tokens),
    "seconds" -> r.seconds.fold(Json.Null)(Json.fromDoubleOrNull),
    "planFingerprint" -> r.planFingerprint.fold(Json.Null)(Json.fromString),
    "sourceSha" -> r.sourceSha.fold(Json.Null)(Json.fromString),
    "workspaceSha" -> r.workspaceSha.fold(Json.Null)(Json.fromString)
  ).dropNullValues

  /** keyHash steht nur für Leser im Datensatz; beim Lesen muss er zum Schlüssel passen. */
  def encode(r: Record): Json = Json.obj(
    "v" -> Json.fromInt(r.schema),
    "eval" -> Json.fromString(r.eval),
    "date" -> Json.fromString(r.date),
    "key" -> encodeKey(r.key),
    "keyHash" -> Json.fromString(r.key.hash),
    "conditions" -> encodeConditions(r.conditions),
    "standing" -> encodeStanding(r.standing),
    "runs" -> Json.arr(r.runs.map(encodeRun)*)
  )

  def encodeInput(i: Input): Json = Json.obj(
    "v" -> Json.fromInt(Version),
    "eval" -> Json.fromString(i.eval),
    "date" -> Json.fromString(i.date),
    "key" -> encodeKey(i.key),
    "conditions" -> encodeConditions(i.conditions),
    "fixtureDir" -> Json.fromString(i.fixtureDir)
  )

  def parse(text: String): Either[String, Record] =
    for
      o <- top(text, "Datensatz", "v", "eval", "date", "key", "keyHash", "conditions", "standing", "runs")
      c = Json.fromJsonObject(o).hcursor
      v <- version(c, "Datensatz")
      eval <- str(c, "eval")
      date <- str(c, "date")
      key <- decodeKey(c.downField("key"))
      keyHash <- str(c, "keyHash")
      _ <- Either.cond(keyHash == key.hash, (), "Datensatz: keyHash passt nicht zum Schlüssel")
      conditions <- decodeConditions(c.downField("conditions"))
      standing <- decodeStanding(c.downField("standing"))
      runs <- c.downField("runs").values.toRight("runs: erwartet eine Liste").flatMap(_.toVector.traverse(j => decodeRun(j.hcursor)))
    yield Record(eval, date, key, conditions, standing, runs, v)

  def parseInput(text: String): Either[String, Input] =
    for
      o <- top(text, "series-input", "v", "eval", "date", "key", "conditions", "fixtureDir")
      c = Json.fromJsonObject(o).hcursor
      _ <- version(c, "series-input")
      eval <- str(c, "eval")
      date <- str(c, "date")
      key <- decodeKey(c.downField("key"))
      conditions <- decodeConditions(c.downField("conditions"))
      fixtureDir <- str(c, "fixtureDir")
    yield Input(eval, date, key, conditions, fixtureDir)

  /** Ein Lauf aus run.json von series.sh und der summary (summary.json, plan-summary.json oder ein leeres Objekt). */
  def runOf(result: Json, summary: Json): Either[String, Run] =
    val r = result.hcursor
    val s = summary.hcursor
    def int(f: String): Int = s.get[Int](f).getOrElse(0)
    for
      outcome <- r.get[String]("outcome").leftMap(_ => "run.json: outcome fehlt")
      _ <- Either.cond(Set("done", "ask", "aborted", "error")(outcome), (), s"run.json: outcome „$outcome“ ist keiner von done, ask, aborted, error")
      check <- r.get[String]("check").leftMap(_ => "run.json: check fehlt").flatMap {
        case "ok"   => Right(true)
        case "fail" => Right(false)
        case other  => Left(s"run.json: check „$other“ ist weder ok noch fail")
      }
      hints <- r.get[Int]("codeHints").leftMap(_ => "run.json: codeHints fehlt")
    yield Run(
      outcome,
      check,
      hints,
      s.get[Long]("steps").getOrElse(0L),
      int("planCalls"),
      int("fillCalls"),
      int("holeRounds"),
      int("gate1Rejections"),
      int("gate2Rejections"),
      int("unusableAnswers"),
      s.get[Boolean]("asked").getOrElse(false),
      s.get[Long]("tokens").getOrElse(0L),
      s.get[Double]("seconds").toOption,
      s.get[String]("planFingerprint").toOption,
      s.get[String]("sourceSha").toOption,
      s.get[String]("workspaceSha").toOption
    )

  /** Verlangte Läufe aus series.json der Eval: genau ein Feld n, ab 1. */
  def parseRequired(text: String): Either[String, Int] =
    for
      o <- top(text, "series.json", "n")
      n <- o("n").flatMap(_.asNumber).flatMap(_.toInt).filter(_ >= 1).toRight("series.json: n erwartet eine ganze Zahl ab 1")
    yield n

  private def top(text: String, at: String, fields: String*): Either[String, JsonObject] =
    for
      json <- parser.parse(text).leftMap(e => s"$at: kein gültiges JSON: ${e.message}")
      o <- json.asObject.toRight(s"$at: erwartet ein Objekt")
      _ <- o.keys.find(k => !fields.contains(k)).map(k => s"$at: Feld „$k“ ist nicht vorgesehen").toLeft(())
    yield o

  private def version(c: ACursor, at: String): Either[String, Int] =
    c.get[Int]("v").leftMap(_ => s"$at: v fehlt").flatMap(v => Either.cond(v >= 1 && v <= Version, v, s"$at: Version $v wird nicht unterstützt (erwartet 1 bis $Version)"))

  private def str(c: ACursor, f: String): Either[String, String] = c.get[String](f).leftMap(_ => s"$f fehlt oder ist kein Text")
  private def int(c: ACursor, f: String): Either[String, Int] = c.get[Int](f).leftMap(_ => s"$f fehlt oder ist keine ganze Zahl")

  private def decodeKey(c: ACursor): Either[String, Key] =
    for
      kind <- str(c, "kind").flatMap(k => SourceKind.values.find(_.toString == k).toRight(s"kind „$k“ ist weder Task noch Flow"))
      source <- str(c, "source")
      sourceSha <- str(c, "sourceSha")
      envelopeSha <- str(c, "envelopeSha")
      fixtureSha <- str(c, "fixtureSha")
      checkSha <- str(c, "checkSha")
      provider <- str(c, "provider")
      model <- str(c, "model")
      effort <- str(c, "effort")
    yield Key(kind, source, sourceSha, envelopeSha, fixtureSha, checkSha, provider, model, effort)

  private def decodeConditions(c: ACursor): Either[String, Conditions] =
    for
      commit <- str(c, "aseCommit")
      dirty <- c.get[List[String]]("dirty").leftMap(_ => "dirty: erwartet eine Liste von Texten")
      planned <- int(c, "planned")
      required <- int(c, "required")
      parallel <- c.get[Boolean]("parallel").leftMap(_ => "parallel: erwartet true oder false")
      reference <- c.get[Option[String]]("reference").leftMap(_ => "reference: erwartet einen Text")
    yield Conditions(commit, dirty, planned, required, parallel, reference)

  private def decodeStanding(c: ACursor): Either[String, Standing] =
    c.get[Boolean]("counts").leftMap(_ => "standing.counts fehlt").flatMap {
      case true => Right(Standing.Counts)
      case false =>
        c.downField("reasons").values.toRight("standing.reasons fehlt").flatMap(_.toList.traverse(j => decodeReason(j.hcursor)))
          .flatMap(rs => NonEmptyList.fromList(rs).map(Standing.DoesNotCount(_)).toRight("standing: zählt nicht, aber ohne Grund"))
    }

  private def decodeReason(c: ACursor): Either[String, NotCounting] =
    str(c, "kind").flatMap {
      case "DirtyTree"        => c.get[List[String]]("files").leftMap(_ => "DirtyTree: files fehlt").map(NotCounting.DirtyTree(_))
      case "TooFewRuns"       => (int(c, "n"), int(c, "required")).mapN(NotCounting.TooFewRuns(_, _))
      case "RunsNotAsPlanned" => (int(c, "found"), int(c, "planned")).mapN(NotCounting.RunsNotAsPlanned(_, _))
      case "FixtureChanged"   => (str(c, "expected"), str(c, "found")).mapN(NotCounting.FixtureChanged(_, _))
      case "StartNotFixture"  => int(c, "runs").map(NotCounting.StartNotFixture(_))
      case "MixedKeys"        => c.get[List[String]]("sourceShas").leftMap(_ => "MixedKeys: sourceShas fehlt").map(NotCounting.MixedKeys(_))
      case other              => Left(s"standing: unbekannter Grund „$other“")
    }

  private def decodeRun(c: ACursor): Either[String, Run] =
    for
      outcome <- str(c, "outcome")
      check <- c.get[Boolean]("check").leftMap(_ => "check: erwartet true oder false")
      hints <- int(c, "codeHints")
      steps <- c.get[Long]("steps").leftMap(_ => "steps fehlt")
      planCalls <- int(c, "planCalls")
      fillCalls <- int(c, "fillCalls")
      holeRounds <- int(c, "holeRounds")
      gate1 <- int(c, "gate1Rejections")
      gate2 <- int(c, "gate2Rejections")
      unusable <- int(c, "unusableAnswers")
      asked <- c.get[Boolean]("asked").leftMap(_ => "asked: erwartet true oder false")
      tokens <- c.get[Long]("tokens").leftMap(_ => "tokens fehlt")
      seconds <- c.get[Option[Double]]("seconds").leftMap(_ => "seconds: erwartet eine Zahl")
      fingerprint <- c.get[Option[String]]("planFingerprint").leftMap(_ => "planFingerprint: erwartet einen Text")
      sourceSha <- c.get[Option[String]]("sourceSha").leftMap(_ => "sourceSha: erwartet einen Text")
      workspaceSha <- c.get[Option[String]]("workspaceSha").leftMap(_ => "workspaceSha: erwartet einen Text")
    yield Run(outcome, check, hints, steps, planCalls, fillCalls, holeRounds, gate1, gate2, unusable, asked, tokens, seconds, fingerprint, sourceSha, workspaceSha)
