package ase.runner

import java.nio.file.{Files, Path}
import scala.util.Try

/** Ob ein Mensch an der Eingabe sitzt (D#95). Die Frage „Freigeben? [y/N]“ gilt nur, wenn die Standardeingabe ein
  * Terminal ist; ein durchgereichtes `y` aus einer Pipe oder Datei gibt nicht frei.
  */
object Terminal:

  /** Unter Linux das Ziel von `/proc/self/fd/0`. Ohne `/proc` `System.console()`, das bis Java 21 nur dann etwas
    * liefert, wenn Ein- und Ausgabe Terminals sind; es urteilt also strenger, nie lockerer.
    */
  def stdin: Boolean =
    Try(Files.readSymbolicLink(Path.of("/proc/self/fd/0")).toString).toOption match
      case Some(target) => isDevice(target)
      case None         => System.console() != null

  /** Pseudo-Terminal, Konsole oder serielles Terminal; nicht `pipe:[…]`, keine Datei, nicht `/dev/null`. */
  def isDevice(target: String): Boolean =
    target.startsWith("/dev/pts/") || target.startsWith("/dev/tty") || target == "/dev/console"

  /** Was `ase run` ohne `--approve` und ohne Terminal meldet. */
  val refusal: String =
    "nicht freigegeben: die Eingabe ist kein Terminal (Pipe, Datei oder Hintergrund).\n" +
      "Ohne Terminal nur mit --approve HASH, dem Hash aus dem Explain oben."
