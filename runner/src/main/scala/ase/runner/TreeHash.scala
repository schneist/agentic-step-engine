package ase.runner

import ase.core.Canonical
import cats.effect.IO

import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Tree-Hash eines Verzeichnisses (D#77, D#78): SHA-256 über „sha256  pfad“ je Datei, nach Pfad sortiert, Pfade
  * relativ mit „/“. `.git/` bleibt draußen, leere Verzeichnisse zählen nicht, ein Link geht mit dem SHA-256 seines
  * Ziels als Text ein und wird nicht verfolgt. Erkennt einen Stand am Inhalt, ohne git. Die reine Funktion `of`, das
  * Einsammeln am Rand in `collect`.
  */
object TreeHash:
  def of(entries: List[(String, String)]): String =
    Canonical.sha256Hex(entries.sortBy(_._1).map((path, sha) => s"$sha  $path\n").mkString)

  def collect(dir: Path): IO[String] = IO.blocking {
    val root = dir.toAbsolutePath.normalize
    val entries = Using.resource(Files.walk(root)) { s =>
      s.iterator.asScala
        .filter(p => p != root && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
        .map(p => root.relativize(p))
        .filterNot(rel => rel.iterator.asScala.exists(_.toString == ".git"))
        .map { rel =>
          val p = root.resolve(rel)
          val sha =
            if Files.isSymbolicLink(p) then Canonical.sha256Hex(Files.readSymbolicLink(p).toString)
            else fileSha(p)
          rel.iterator.asScala.mkString("/") -> sha
        }
        .toList
    }
    of(entries)
  }

  private def fileSha(p: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    Using.resource(Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) { in =>
      val buf = new Array[Byte](1 << 16)
      var n = in.read(buf)
      while n >= 0 do
        md.update(buf, 0, n)
        n = in.read(buf)
    }
    md.digest.map(b => f"${b & 0xff}%02x").mkString
