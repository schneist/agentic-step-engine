package ase.core

import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal

/** Eine Einhängung (D#88): ein Pfad außerhalb des Arbeitsverzeichnisses, den die Sandbox einem Programm sichtbar macht.
  * path: absolut, "~" oder "~/…" (unter HOME) oder relativ zum Arbeitsverzeichnis (etwa "../.cloud-stub").
  */
final case class Mount(path: String, writable: Boolean)

/** Was ein Programm über den Standard der Sandbox hinaus bekommt (D#88). */
final case class Extra(net: Boolean, mounts: List[Mount])

/** Sandbox je Programmaufruf (D#88). On: jeder Prozess eines Shell-Aufrufs läuft in bubblewrap mit eigenen
  * Namespaces; er sieht Systemverzeichnisse nur lesbar, das Arbeitsverzeichnis schreibbar, ein leeres HOME und kein
  * Netz, dazu, was extras für sein Programm nennt. Skripte („./…“) bekommen nie etwas dazu. Off: wie vor D#88, nur
  * bewusst über die Konfiguration. Eine Eigenschaft des Interpreters wie die Umgebung aus D#36, nicht der Hülle und
  * nicht des Terms.
  */
enum Sandbox:
  case Off
  case On(extras: Map[String, Extra])

object Sandbox:
  val default: Sandbox = On(Map.empty)

  /** Zusätze eines Aufrufs; ein Skript bekommt keine, auch wenn die Konfiguration es versuchte. */
  def extraFor(s: Sandbox, cmd: String): Extra = s match
    case On(extras) if !cmd.startsWith("./") => extras.getOrElse(cmd, Extra(net = false, Nil))
    case _                                   => Extra(net = false, Nil)

  /** Für Explain und Lauf-Log: eine Zeile, was die Sandbox zulässt. */
  def describe(s: Sandbox): String = s match
    case Off => "AUS (Konfiguration sandbox: false): Programme sehen alles, was der Nutzer sieht, auch Netz und Zugangsdaten"
    case On(extras) =>
      val base = "jeder Aufruf sieht nur das Arbeitsverzeichnis und Systemverzeichnisse (lesbar), ohne Netz"
      val more = extras.toList.sortBy(_._1).filter((_, e) => e.net || e.mounts.nonEmpty).map { (cmd, e) =>
        val parts = Option.when(e.net)("Netz").toList ++ e.mounts.map(m => s"${m.path} ${if m.writable then "schreibbar" else "lesbar"}")
        s"$cmd: ${parts.mkString(", ")}"
      }
      if more.isEmpty then base else s"$base; dazu ${more.mkString(" · ")}"

  /** Ein Pfad aus der Konfiguration, aufgelöst gegen HOME und das Arbeitsverzeichnis. */
  def resolve(path: String, root: Path, home: Option[Path]): Either[String, Path] =
    if path == "~" || path.startsWith("~/") then
      home.toRight(s"$path: HOME ist nicht gesetzt").map(h => h.resolve(path.drop(2)).normalize)
    else if path.startsWith("~") then Left(s"$path: nur ~ oder ~/… (HOME), kein ~name")
    else if path.startsWith("/") then Right(Path.of(path).normalize)
    else Right(root.resolve(path).normalize)

  /** Einhängungen eines Programms, geprüft gegen das Arbeitsverzeichnis: keine, die es enthält oder darin liegt (sie
    * gäbe mehr frei als das Arbeitsverzeichnis oder wäre überflüssig). Schreibbare, die fehlen, legt open an; lesbare,
    * die fehlen, entfallen.
    */
  def mounts(extra: Extra, root: Path, home: Option[Path]): Either[String, List[(Path, Boolean)]] =
    extra.mounts.foldLeft[Either[String, List[(Path, Boolean)]]](Right(Nil)) { (acc, m) =>
      acc.flatMap(done =>
        resolve(m.path, root, home).flatMap { p =>
          if root.startsWith(p) then Left(s"${m.path}: enthält das Arbeitsverzeichnis")
          else if p.startsWith(root) then Left(s"${m.path}: liegt im Arbeitsverzeichnis")
          else Right(done :+ (p, m.writable))
        }
      )
    }

  private val nofollow = LinkOption.NOFOLLOW_LINKS

  /** Liegt a über b oder ist es b? Dann darf a nicht ganz eingehängt werden. */
  private def covers(a: Path, b: Path): Boolean = b.startsWith(a)

  /** Die Argumente für bwrap vor dem Programm. Reihenfolge zählt: Spätere Einhängungen liegen über früheren, darum
    * zuerst das System, dann leere Verzeichnisse für /tmp und HOME, dann Programme und Zusätze, dann das
    * Arbeitsverzeichnis und zuletzt dessen .git nur lesbar darüber (D#91), bei einem Worktree auch die
    * Git-Verzeichnisse außerhalb (`gitOutside`, D#96).
    */
  def argv(
      extra: Extra,
      mounts: List[(Path, Boolean)],
      root: Path,
      home: Option[Path],
      pathDirs: List[String],
      javaHome: Option[String],
      gitOutside: List[Path] = Nil
  ): List[String] =
    def exists(p: Path) = Files.exists(p, nofollow)
    val system = List("/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32").map(Path.of(_)).flatMap { p =>
      if Files.isSymbolicLink(p) then List("--symlink", Files.readSymbolicLink(p).toString, p.toString)
      else if Files.isDirectory(p, nofollow) then List("--ro-bind", p.toString, p.toString)
      else Nil
    }
    // Geheimnisse in /etc überdecken; /etc selbst brauchen Programme (Zertifikate, Namensauflösung, alternatives)
    val secrets = List("/etc/shadow", "/etc/gshadow", "/etc/shadow-", "/etc/gshadow-", "/etc/sudoers")
      .filter(f => Files.isRegularFile(Path.of(f), nofollow)).flatMap(f => List("--ro-bind", "/dev/null", f))
    // in WSL zeigt /etc/resolv.conf nach /mnt/wsl; mit Netz braucht das Programm das Ziel
    val resolv =
      if !extra.net then Nil
      else
        try
          val r = Path.of("/etc/resolv.conf").toRealPath()
          if r.startsWith("/etc") then Nil else List("--ro-bind-try", r.toString, r.toString)
        catch case NonFatal(_) => Nil
    // ein leeres HOME; liegt das Arbeitsverzeichnis darunter, hängt es zuletzt darüber
    val homeDir = home.filter(_.getNameCount > 0).toList.flatMap(h => List("--tmpfs", h.toString))
    // Programmverzeichnisse lesbar; keines, das HOME oder das Arbeitsverzeichnis ganz enthielte
    val programs = (pathDirs ++ javaHome.toList).distinct.map(Path.of(_).normalize).filter(d =>
      d.getNameCount > 0 && !covers(d, root) && home.forall(h => !covers(d, h))
    ).flatMap(d => List("--ro-bind-try", d.toString, d.toString))
    val extras = mounts.flatMap((p, w) => if w then List("--bind", p.toString, p.toString) else List("--ro-bind-try", p.toString, p.toString))
    // .git nur lesbar (D#91): Wer .git/config, Hooks oder Filter schreibt, bringt Code dorthin, wo der Nutzer nach dem
    // Lauf `git` aufruft, außerhalb der Sandbox. Verzeichnis oder Datei (Worktree). Ein Symlink bleibt, wie er ist:
    // sein Ziel liegt außerhalb des Arbeitsverzeichnisses und ist in der Sandbox gar nicht eingehängt.
    val git = root.resolve(".git")
    val gitDir =
      if Files.isDirectory(git, nofollow) || Files.isRegularFile(git, nofollow) then List("--ro-bind", git.toString, git.toString)
      else Nil
    // Worktree (D#96): sein Git-Verzeichnis und das gemeinsame liegen außerhalb, git braucht beide, nur lesbar
    val worktree = gitOutside.flatMap(d => List("--ro-bind", d.toString, d.toString))
    List("--unshare-all") ++ Option.when(extra.net)("--share-net").toList ++
      List("--die-with-parent", "--new-session", "--ro-bind", "/usr", "/usr") ++ system ++
      List("--ro-bind", "/etc", "/etc") ++ secrets ++ resolv ++
      List("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp") ++ homeDir ++ programs ++ extras ++
      List("--bind", root.toString, root.toString) ++ gitDir ++ worktree ++ List("--chdir", root.toString)
