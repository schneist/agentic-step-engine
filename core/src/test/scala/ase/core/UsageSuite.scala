package ase.core

class UsageSuite extends munit.FunSuite:
  private val env = Envelope(Set(OpKind.ReadFile, OpKind.EditFile, OpKind.Hole), ".", 6, 1, Set.empty)
  private val inner = Envelope(Set(OpKind.ReadFile), "src", 2, 0, Set.empty)
  private def id(xs: Int*) = StepId(xs.toVector)
  private def read(at: StepId, path: String) =
    Event.Step(at, Call.ReadFile(path), Reply(List(path), Right(StepResult(None, Some("x"))), ""))
  private val done = Term.Done("ok", Origin.Model("fill-1"))

  test("Verschachtelte Agentenschritte zählen für den äußeren; ihre Teilterme nicht in dessen Formen (D#74)"):
    val events = Vector(
      Event.Hole(id(2), "außen", env),
      Event.Fill(id(2), 1, "fill-1", ModelReply.Answer("{}", 10)),
      Event.FillRejected(id(2), 1, Gate.DryRun, None, List("zu weit"), "zu weit"),
      Event.Fill(id(2), 2, "fill-2", ModelReply.Answer("{}", 20)),
      Event.FillAccepted(id(2), 2, TermHash.of(done, env), 1, 6, done),
      read(id(2, 1, 1), "src/A.scala"),
      Event.Hole(id(2, 1, 2), "innen", inner),
      Event.Fill(id(2, 1, 2), 1, "fill-3", ModelReply.Answer("{}", 5)),
      Event.FillAccepted(id(2, 1, 2), 1, TermHash.of(done, inner), 1, 2, done),
      read(id(2, 1, 2, 1, 1), "src/B.scala"),
      Event.HoleDone(id(2, 1, 2), "innen fertig"),
      Event.HoleDone(id(2), "außen fertig"),
      read(id(3), "README.md")
    )
    val calls = Usage.of(events)
    assertEquals(calls.size, 1)
    val c = calls.head
    assertEquals(c.ops, Set(OpKind.ReadFile, OpKind.Hole))
    assertEquals(c.paths, Set("src/A.scala", "src/B.scala"))
    // wie in der Engine (D#14): der ReadFile außen, der angenommene Teilterm des inneren und dessen ReadFile
    assertEquals(c.steps, 3)
    assertEquals((c.fills, c.tokens), (3, 35L))
    assertEquals(c.rejected, Map(Gate.DryRun -> 1))
    assertEquals(c.shapes.size, 1)
    assertEquals(Usage.commonPrefix(c.paths), Some("src"))

  test("Ein Abbruch ohne HoleDone beendet den Aufruf; ein Hole direkt danach beginnt einen neuen"):
    val events = Vector(
      Event.Hole(id(1), "a", env),
      read(id(1, 1, 1), "x.txt"),
      Event.Hole(id(2), "b", inner),
      Event.End(Outcome.Done, 2)
    )
    assertEquals(Usage.of(events).map(c => (c.at.show, c.paths)), Vector(("1", Set("x.txt")), ("2", Set.empty[String])))

  test("commonPrefix: eine Datei, ein gemeinsames Verzeichnis, nichts Gemeinsames"):
    assertEquals(Usage.commonPrefix(Set.empty), None)
    assertEquals(Usage.commonPrefix(Set("PRUEFUNG_V133.md")), Some("PRUEFUNG_V133.md"))
    assertEquals(Usage.commonPrefix(Set("src/a/B.scala", "src/a/C.scala")), Some("src/a"))
    assertEquals(Usage.commonPrefix(Set("src/A.scala", "README.md")), Some("."))
    // ein ungültiger Pfad (etwa mit Backslash) macht nichts enger (Review)
    assertEquals(Usage.commonPrefix(Set("README.md", "src\\Main.scala")), None)
