package ase.core

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import scala.util.Try

/** Ein Arbeitsverzeichnis, das ein Git-Worktree ist (D#96): `.git` ist eine Datei `gitdir: …`, das Git-Verzeichnis
  * und das gemeinsame Verzeichnis (`commondir`) liegen außerhalb. `git` braucht beide; die Sandbox hängt sie nur
  * lesbar ein, das umfassende zuerst.
  */
final case class GitRepo(outside: List[Path])

object GitRepo:
  private val nofollow = LinkOption.NOFOLLOW_LINKS
  private val MaxFileBytes = 4096

  /** `root` ist aufgelöst (`toRealPath`). Ein Symlink `.git` zählt nicht, ebenso wenig ein Git-Verzeichnis im
    * Arbeitsverzeichnis: Das ist schreibbar, und wer dort die Konfiguration schreibt (etwa einen Filter), startet über
    * `git` beliebige Programme.
    */
  def worktree(root: Path): Option[GitRepo] =
    val file = root.resolve(".git")
    for
      _ <- Option.when(Files.isRegularFile(file, nofollow))(())
      line <- firstLine(file)
      target <- Option.when(line.startsWith("gitdir: "))(line.stripPrefix("gitdir: ").trim).filter(_.nonEmpty)
      gitDir <- external(root, Try(root.resolve(target).normalize).toOption)
      // ein Git-Verzeichnis hat HEAD; eines ohne commondir (Submodul) ist zugleich das gemeinsame
      _ <- Option.when(Files.isRegularFile(gitDir.resolve("HEAD")))(())
      common <-
        if Files.isRegularFile(gitDir.resolve("commondir"), nofollow) then
          firstLine(gitDir.resolve("commondir")).flatMap(c => external(root, Try(gitDir.resolve(c.trim).normalize).toOption))
        else Some(gitDir)
    yield GitRepo(List(common, gitDir).distinct.sortBy(_.getNameCount))

  /** Aufgelöst ein Verzeichnis, das weder im Arbeitsverzeichnis liegt noch es enthält. */
  private def external(root: Path, p: Option[Path]): Option[Path] =
    p.flatMap(q => Try(q.toRealPath()).toOption)
      .filter(r => Files.isDirectory(r) && !r.startsWith(root) && !root.startsWith(r))

  private def firstLine(f: Path): Option[String] =
    Try {
      val in = Files.newInputStream(f)
      try new String(in.readNBytes(MaxFileBytes), UTF_8)
      finally in.close()
    }.toOption.flatMap(_.linesIterator.nextOption())
