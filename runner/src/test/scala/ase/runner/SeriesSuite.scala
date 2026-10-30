package ase.runner

import ase.core.Canonical
import ase.runner.Series.*
import cats.data.NonEmptyList
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Serien als Datensatz (D#77): Stand, Intervall, Vergleich, Zeile, Datensatz-Format, Befehle. */
class SeriesSuite extends munit.ScalaCheckSuite:
  private val key = Key(SourceKind.Task, "task.md", "a" * 64, "b" * 64, "c" * 64, "d" * 64, "ClaudeCode", "claude-opus-5-5", "high")
  private val clean = Conditions("37eba77", Nil, 3, 3, false, None)
  private val input = Input("1-todos", "2026-10-30", key, clean, "/tmp/fixture")

  private def run(check: Boolean = true, tokens: Long = 30000, seconds: Option[Double] = Some(10), asked: Boolean = false) =
    Run("done", check, 0, 4, 1, 1, 1, 0, 0, 0, asked, tokens, seconds, Some("f" * 64), Some(key.sourceSha))

  private val runs = Vector(run(tokens = 30000, seconds = Some(12)), run(tokens = 31000, seconds = Some(10), asked = true), run(check = false, tokens = 29500, seconds = Some(15)))
  private val record = Series.build(input, key.fixtureSha, runs)

  test("Schlüssel: Hash über das kanonische JSON der Felder"):
    val canonical =
      s"""{"checkSha":"${"d" * 64}","effort":"high","envelopeSha":"${"b" * 64}","fixtureSha":"${"c" * 64}","kind":"Task","model":"claude-opus-5-5","provider":"ClaudeCode","source":"task.md","sourceSha":"${"a" * 64}"}"""
    assertEquals(key.hash, Canonical.sha256Hex(canonical))

  test("Stand: zählt nur ohne Gründe; jeder Grund wird genannt, nichts weggelassen"):
    assertEquals(record.standing, Standing.Counts)
    val dirty = Series.build(
      input.copy(conditions = clean.copy(dirty = List("findings.md"), required = 5)),
      "e" * 64,
      runs :+ run().copy(sourceSha = Some("9" * 64), workspaceSha = Some("8" * 64))
    )
    dirty.standing match
      case Standing.DoesNotCount(rs) =>
        assertEquals(
          rs.toList,
          List(
            NotCounting.DirtyTree(List("findings.md")),
            NotCounting.TooFewRuns(4, 5),
            NotCounting.RunsNotAsPlanned(4, 3),
            NotCounting.FixtureChanged("c" * 64, "e" * 64),
            NotCounting.StartNotFixture(1),
            NotCounting.MixedKeys(List("9" * 64))
          )
        )
      case Standing.Counts => fail("sollte nicht zählen")
    assertEquals(dirty.runs.size, 4)
    // Läufe ohne sourceSha (nichts lief) machen keine Mischung; ein Start mit dem Fixture ist kein Grund
    assertEquals(Series.build(input, key.fixtureSha, runs.map(_.copy(sourceSha = None))).standing, Standing.Counts)
    assertEquals(Series.build(input, key.fixtureSha, runs.map(_.copy(workspaceSha = Some(key.fixtureSha)))).standing, Standing.Counts)

  test("Wilson-Intervall: bekannte Werte; 10 von 10 heißt über 72 %, 5 von 5 über 57 %"):
    assertEqualsDouble(Series.wilson(10, 10).low, 0.7225, 0.001)
    assertEqualsDouble(Series.wilson(5, 5).low, 0.5655, 0.001)
    assertEqualsDouble(Series.wilson(10, 10).high, 1.0, 1e-9)
    assertEquals(Series.wilson(0, 0), Interval(0, 1))

  property("Wilson-Intervall enthält k/n und liegt in [0, 1]"):
    forAll(Gen.choose(1, 200).flatMap(n => Gen.choose(0, n).map((_, n)))) { (k, n) =>
      val i = Series.wilson(k, n)
      val p = k.toDouble / n
      0 <= i.low && i.low <= p + 1e-9 && p <= i.high + 1e-9 && i.high <= 1
    }

  private val series: Gen[Record] =
    for
      n <- Gen.choose(1, 12)
      ok <- Gen.choose(0, n)
      tokens <- Gen.listOfN(n, Gen.choose(1000L, 90000L))
      parallel <- Gen.oneOf(true, false)
    yield
      val rs = tokens.zipWithIndex.map((t, i) => run(check = i < ok, tokens = t, seconds = Some(t / 1000.0))).toVector
      Series.build(input.copy(conditions = clean.copy(planned = n, required = 1, parallel = parallel)), key.fixtureSha, rs)

  private def flipped(v: Verdict): Verdict = v match
    case Verdict.Better => Verdict.Worse
    case Verdict.Worse  => Verdict.Better
    case other          => other

  property("Vergleich ist symmetrisch: besser in die eine Richtung heißt schlechter in die andere"):
    forAll(series, series) { (a, b) =>
      (Comparison.of(a, b), Comparison.of(b, a)) match
        case (Right(ab), Right(ba)) =>
          Prop(ab.success == flipped(ba.success) && ab.tokens == flipped(ba.tokens) && ab.seconds == flipped(ba.seconds))
        case other => Prop.falsified :| other.toString
    }

  property("Eine Serie gegen sich selbst ist Rauschen"):
    forAll(series) { a =>
      Comparison.of(a, a).map(c => (c.success, c.tokens, c.seconds)) == Right((Verdict.Noise, Verdict.Noise, Verdict.Noise))
    }

  test("Vergleich: besser und schlechter nur ohne Überlappung; Dauer nur bei gleicher Parallelität"):
    val cheap = Series.build(input, key.fixtureSha, Vector.fill(3)(run(tokens = 10000, seconds = Some(5))))
    val cmp = Comparison.of(record, cheap).toOption.get
    assertEquals((cmp.success, cmp.tokens, cmp.seconds), (Verdict.Noise, Verdict.Better, Verdict.Better))
    val par = cheap.copy(conditions = cheap.conditions.copy(parallel = true))
    assertEquals(Comparison.of(record, par).map(_.seconds), Right(Verdict.NotCompared("verschiedene Parallelität")))
    val many = Series.build(input.copy(conditions = clean.copy(planned = 30, required = 30)), key.fixtureSha, Vector.fill(30)(run()))
    val few = Series.build(input.copy(conditions = clean.copy(planned = 30, required = 30)), key.fixtureSha, Vector.fill(30)(run(check = false)))
    assertEquals(Comparison.of(few, many).map(_.success), Right(Verdict.Better))

  test("Vergleich: nicht bei anderem Schlüssel (alle Felder genannt), anderem Schema oder wenn eine Seite nicht zählt"):
    val other = record.copy(key = key.copy(model = "claude-sonnet-5-5", fixtureSha = "e" * 64))
    assertEquals(
      Comparison.of(record, other).left.map(_.render),
      Left(s"nicht vergleichbar, der Schlüssel unterscheidet sich:\n  fixtureSha: ${"c" * 12}… gegen ${"e" * 12}…\n  model: claude-opus-5-5 gegen claude-sonnet-5-5")
    )
    assertEquals(Comparison.of(record, record.copy(schema = 2)), Left(NotComparable.SchemaMismatch(1, 2)))
    val dirty = record.copy(standing = Standing.DoesNotCount(NonEmptyList.of(NotCounting.DirtyTree(List("a.scala")))))
    assertEquals(Comparison.of(record, dirty).left.map(_.render), Left("nicht vergleichbar: der Kandidat zählt nicht: Arbeitsbaum geändert (a.scala)"))

  test("M#-Zeile aus dem Datensatz (playbook/evals.md)"):
    assertEquals(
      Series.line("M#19", record),
      "- M#19 2026-10-30 Eval 1-todos, Aufgabe task.md · Modell claude-opus-5-5, effort high, über ClaudeCode · Commit 37eba77 · n=3" +
        " · Erfolg 2/3 (95 %: 21 bis 94 %) · Tor1 0 · Tor2 0 · Ask 1 · Tokens ø 30.2k (Median 30.0k, 29.5k bis 31.0k)" +
        s" · Dauer Median 12 s, 10 s bis 15 s · Referenz-Fingerprint – · Code-Hinweis 0/3 · Schlüssel ${key.hash.take(8)} · zählt"
    )
    val flow = record.copy(key = key.copy(kind = SourceKind.Flow, source = "pruefkette.json"), conditions = clean.copy(parallel = true, reference = Some("f" * 64)))
    val l = Series.line("M#20", flow)
    assert(l.contains("Flow pruefkette.json") && l.contains("n=3, parallel") && l.contains("Referenz-Fingerprint 3/3"), l)
    assertEquals(Series.idOf("M18.json"), Some("M#18"))
    assertEquals(Series.idOf("series.json"), None)

  test("Datensatz: Roundtrip; keyHash muss passen, fremde Felder und neuere Versionen abgelehnt"):
    val text = Series.encode(record).spaces2
    assertEquals(Series.parse(text), Right(record))
    val notCounting = Series.build(input.copy(conditions = clean.copy(dirty = List("x"))), "e" * 64, runs)
    assertEquals(Series.parse(Series.encode(notCounting).noSpaces), Right(notCounting))
    val tampered = Series.encode(record).mapObject(_.add("keyHash", Json.fromString("0" * 64))).noSpaces
    assertEquals(Series.parse(tampered), Left("Datensatz: keyHash passt nicht zum Schlüssel"))
    assert(Series.parse(Series.encode(record).mapObject(_.add("extra", Json.True)).noSpaces).isLeft)
    assert(Series.parse(Series.encode(record).mapObject(_.add("v", Json.fromInt(2))).noSpaces).isLeft)
    assertEquals(Series.parseInput(Series.encodeInput(input).noSpaces), Right(input))

  test("Lauf aus run.json und summary.json, plan-summary.json oder nichts"):
    val result = Json.obj("outcome" -> Json.fromString("done"), "check" -> Json.fromString("ok"), "codeHints" -> Json.fromInt(2))
    val summary = Json.obj(
      "outcome" -> Json.fromString("done"), "steps" -> Json.fromInt(7), "planCalls" -> Json.fromInt(1), "fillCalls" -> Json.fromInt(2),
      "holeRounds" -> Json.fromInt(3), "gate1Rejections" -> Json.fromInt(1), "gate2Rejections" -> Json.fromInt(2), "unusableAnswers" -> Json.fromInt(0),
      "asked" -> Json.False, "tokens" -> Json.fromLong(45000), "seconds" -> Json.fromDoubleOrNull(12.5),
      "planFingerprint" -> Json.fromString("f" * 64), "sourceSha" -> Json.fromString("a" * 64), "workspaceSha" -> Json.fromString("c" * 64)
    )
    assertEquals(Series.runOf(result, summary), Right(Run("done", true, 2, 7, 1, 2, 3, 1, 2, 0, false, 45000, Some(12.5), Some("f" * 64), Some("a" * 64), Some("c" * 64))))
    val full = Series.runOf(result, summary).toOption.get
    assertEquals(Series.parse(Series.encode(record.copy(runs = Vector(full))).noSpaces).map(_.runs), Right(Vector(full)))
    val planOnly = Json.obj("planOutcome" -> Json.fromString("rejected"), "planCalls" -> Json.fromInt(3), "gate1Rejections" -> Json.fromInt(3), "tokens" -> Json.fromLong(9000))
    val aborted = result.mapObject(_.add("outcome", Json.fromString("aborted")).add("check", Json.fromString("fail")))
    assertEquals(Series.runOf(aborted, planOnly), Right(Run("aborted", false, 2, 0, 3, 0, 0, 3, 0, 0, false, 9000, None, None, None)))
    assertEquals(Series.runOf(result, Json.obj()).map(_.tokens), Right(0L))
    assert(Series.runOf(result.mapObject(_.add("outcome", Json.fromString("fertig"))), summary).isLeft)
    assertEquals(Series.parseRequired("""{"n": 5}"""), Right(5))
    assert(Series.parseRequired("""{"n": 0}""").isLeft)

  test("Befehlszeile: series begin, end, show und compare"):
    assertEquals(
      Cli.parse(List("series", "begin", "--eval", "evals/1-todos", "--commit", "37eba77", "--status", "s.txt", "--n", "5", "--parallel", "--out", "i.json")),
      Right(Command.SeriesBegin(Path.of("evals/1-todos"), None, "37eba77", Path.of("s.txt"), 5, true, None, None, Path.of("i.json")))
    )
    assert(Cli.parse(List("series", "begin", "--eval", "e", "--commit", "HEAD", "--status", "s", "--n", "5", "--out", "i")).isLeft)
    assert(Cli.parse(List("series", "begin", "--eval", "e", "--commit", "37eba77", "--status", "s", "--n", "0", "--out", "i")).isLeft)
    assert(Cli.parse(List("series", "begin", "--eval", "e", "--commit", "37eba77", "--status", "s", "--n", "5", "--no-ask", "--out", "i")).isLeft)
    assertEquals(Cli.parse(List("series", "end", "--input", "i.json", "--out", "M19.json", "runs")), Right(Command.SeriesEnd(Path.of("i.json"), Path.of("M19.json"), List(Path.of("runs")))))
    assert(Cli.parse(List("series", "end", "--input", "i.json", "--out", "M19.json")).isLeft)
    assertEquals(Cli.parse(List("compare", "M17.json", "M18.json")), Right(Command.Compare(Path.of("M17.json"), Path.of("M18.json"))))
    assert(Cli.parse(List("run", "--plan", "p", "--envelope", "e", "--workspace", "w", "--parallel")).isLeft)

  test("ase series begin und end an einem Verzeichnis: Fixture ohne .git gehasht, Änderung am Fixture zählt nicht"):
    val tmp = Files.createTempDirectory("series")
    def write(p: Path, s: String): Unit = { Files.createDirectories(p.getParent); Files.writeString(p, s, UTF_8) }
    val eval = tmp.resolve("1-todos")
    write(eval.resolve("fixture/src/A.scala"), "object A\n")
    write(eval.resolve("fixture/.git/HEAD"), "ref: refs/heads/main\n")
    write(eval.resolve("task.md"), "Liste die TODOs\n")
    write(eval.resolve("check.sh"), "exit 0\n")
    write(eval.resolve("series.json"), """{"n": 2}""")
    write(eval.resolve("envelope.json"), """{"allowedOps":["ReadFile","Hole"],"pathPrefix":".","maxSteps":5,"maxDepth":1,"shellAllowlist":[]}""")
    write(tmp.resolve("status.txt"), "")
    val out = tmp.resolve("out")
    for i <- 1 to 2 do
      write(out.resolve(s"run-$i/run.json"), """{"outcome":"done","check":"ok","codeHints":0}""")
      write(out.resolve(s"run-$i/summary.json"), s"""{"outcome":"done","tokens":${1000 * i},"seconds":$i.0}""")
    def ase(args: String*) = Main.run(args.toList).unsafeRunSync()
    assertEquals(ase("series", "begin", "--eval", eval.toString, "--commit", "37eba77", "--status", tmp.resolve("status.txt").toString, "--n", "2", "--out", tmp.resolve("input.json").toString).code, 0)
    val in = Series.parseInput(Files.readString(tmp.resolve("input.json"))).toOption.get
    assertEquals((in.eval, in.conditions.required, in.key.kind, in.key.source), ("1-todos", 2, SourceKind.Task, "task.md"))
    assertEquals(in.key.fixtureSha, TreeHash.of(List("src/A.scala" -> Canonical.sha256Hex("object A\n"))))
    // ein Fixture, das selbst ein Link ist, zählt mit seinem Ziel
    val linked = tmp.resolve("linked")
    Files.createDirectories(linked)
    Files.createSymbolicLink(linked.resolve("fixture"), eval.resolve("fixture"))
    List("task.md", "check.sh", "envelope.json").foreach(f => Files.copy(eval.resolve(f), linked.resolve(f)))
    assertEquals(ase("series", "begin", "--eval", linked.toString, "--commit", "37eba77", "--status", tmp.resolve("status.txt").toString, "--n", "2", "--out", tmp.resolve("linked.json").toString).code, 0)
    assertEquals(Series.parseInput(Files.readString(tmp.resolve("linked.json"))).map(_.key.fixtureSha), Right(in.key.fixtureSha))
    assertEquals(ase("series", "end", "--input", tmp.resolve("input.json").toString, "--out", tmp.resolve("M1.json").toString, out.toString).code, 0)
    val m1 = Series.parse(Files.readString(tmp.resolve("M1.json"))).toOption.get
    assertEquals((m1.standing, m1.runs.map(_.tokens)), (Standing.Counts, Vector(1000L, 2000L)))
    write(eval.resolve("fixture/src/A.scala"), "object A { val x = 1 }\n")
    assertEquals(ase("series", "end", "--input", tmp.resolve("input.json").toString, "--out", tmp.resolve("M2.json").toString, out.toString).code, 0)
    val m2 = Series.parse(Files.readString(tmp.resolve("M2.json"))).toOption.get
    assert(m2.standing.isInstanceOf[Standing.DoesNotCount], m2.standing.toString)
    assertEquals(ase("compare", tmp.resolve("M1.json").toString, tmp.resolve("M1.json").toString).code, 0)
    assertEquals(ase("compare", tmp.resolve("M1.json").toString, tmp.resolve("M2.json").toString).code, 1)
