package ase.runner

import ase.core.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global

/** D#95: die Frage „Freigeben?“ nur an einem Terminal (O#51 a). */
class TerminalSuite extends munit.FunSuite:
  private val h = Origin.Human
  private val root = Envelope(Set(OpKind.ListDir), ".", 2, 0, Set.empty)
  private val checked = DryRun.plan(Term.ListDir("src", h), root, CheckConfig.default).fold(v => fail(v.toString), identity)

  /** Eine Antwort, die zählt, wie oft sie gelesen wurde. */
  private def answer(text: IO[String]): (IO[String], () => Int) =
    var reads = 0
    (IO.delay(reads += 1) >> text, () => reads)

  private def approve(confirmed: Option[String], terminal: Boolean, text: IO[String]) =
    val (a, reads) = answer(text)
    (Main.approve(checked, confirmed, terminal, a).unsafeRunSync().map(_.termHash), reads())

  test("Gerät: Pseudo-Terminal und Konsole ja; Pipe, Datei, /dev/null nein"):
    List("/dev/pts/3", "/dev/tty1", "/dev/ttyS0", "/dev/console").foreach(t => assert(Terminal.isDevice(t), t))
    List("pipe:[1321]", "socket:[77]", "/dev/null", "/tmp/antwort.txt", "/dev/ptsx", "").foreach(t => assert(!Terminal.isDevice(t), t))

  test("ohne Terminal und ohne --approve: nicht freigegeben, auch wenn ein y anliegt; gelesen wird nichts"):
    assertEquals(approve(None, terminal = false, IO.pure("y")), (None, 0))

  test("am Terminal: y und j geben den gezeigten Hash frei, alles andere und das Ende der Eingabe nicht"):
    assertEquals(approve(None, terminal = true, IO.pure(" Y ")), (Some(checked.termHash), 1))
    assertEquals(approve(None, terminal = true, IO.pure("j")), (Some(checked.termHash), 1))
    assertEquals(approve(None, terminal = true, IO.pure("ja")), (None, 1))
    assertEquals(approve(None, terminal = true, IO.raiseError(new java.io.EOFException)), (None, 1))

  test("mit --approve braucht es kein Terminal; ein anderer Hash gibt nicht frei"):
    assertEquals(approve(Some(checked.termHash.hex), terminal = false, IO.pure("n")), (Some(checked.termHash), 0))
    val other = "0" * 64
    assume(other != checked.termHash.hex)
    assertEquals(approve(Some(other), terminal = false, IO.pure("y")), (None, 0))
