package ase.core

import io.circe.Json

/** Eingabe-Schema für submit_term (D#10), von Hand. `strict: true` erlaubt keine Rekursion, daher ausgerollt:
  * Term_k verweist nur auf Term_{k-1}, Term_0 hat keine Kinder; ebenso Pred_k. Kein origin: das setzt die Engine (D#26).
  */
object Schema:
  def termSchema(nesting: Int): Json =
    val leaves = List(
      obj("ReadFile", "path" -> str),
      obj("ListDir", "path" -> str),
      obj("WriteFile", "path" -> str, "content" -> str),
      obj("EditFile", "path" -> str, "expectedOld" -> str, "replacement" -> str),
      obj("MoveFile", "from" -> str, "to" -> str),
      obj("Shell", "cmd" -> str, "args" -> strArray),
      obj("Ask", "question" -> str),
      obj("Done", "summary" -> str),
      obj("Hole", "goal" -> str, "envelope" -> ref("Envelope"))
    )
    val predLeaves = List(
      obj("FileExists", "path" -> str),
      obj("LastExitCode", "code" -> int),
      obj("LastOutputContains", "text" -> str),
      obj("LastOutputEmpty")
    )
    val leafNames = leaves.map(_._1)
    val predLeafNames = predLeaves.map(_._1)
    val top = s"Pred_$nesting"

    val terms = (0 to nesting).flatMap { k =>
      val composites =
        if k == 0 then Nil
        else
          val below = ref(s"Term_${k - 1}")
          List(
            s"Seq_$k" -> obj("Seq", "steps" -> Json.obj("type" -> Json.fromString("array"), "items" -> below))._2,
            s"If_$k" -> obj("If", "pred" -> ref(top), "thenT" -> below, "elseT" -> below)._2,
            s"RepeatUntil_$k" -> obj("RepeatUntil", "body" -> below, "until" -> ref(top), "max" -> int)._2
          )
      composites :+ (s"Term_$k" -> anyOf(leafNames ++ composites.map(_._1)))
    }
    val preds = (0 to nesting).flatMap { k =>
      val composites =
        if k == 0 then Nil
        else
          val below = ref(s"Pred_${k - 1}")
          List(s"Not_$k" -> obj("Not", "p" -> below)._2, s"And_$k" -> obj("And", "p" -> below, "q" -> below)._2)
      composites :+ (s"Pred_$k" -> anyOf(predLeafNames ++ composites.map(_._1)))
    }
    val envelope = "Envelope" -> closed(
      "allowedOps" -> Json.obj(
        "type" -> Json.fromString("array"),
        "items" -> Json.obj("enum" -> Json.arr(OpKind.values.toList.map(o => Json.fromString(o.toString))*))
      ),
      "pathPrefix" -> str,
      "maxSteps" -> int,
      "maxDepth" -> int,
      "shellAllowlist" -> Json.obj("type" -> Json.fromString("array"), "items" -> ref("AllowedCommand"))
    )
    val allowedCommand = "AllowedCommand" -> closed("binary" -> str, "requiredPrefixArgs" -> strArray)

    Json.obj(
      "type" -> Json.fromString("object"),
      "properties" -> Json.obj("term" -> ref(s"Term_$nesting")),
      "required" -> Json.arr(Json.fromString("term")),
      "additionalProperties" -> Json.False,
      "$defs" -> Json.obj(((leaves ++ predLeaves ++ terms ++ preds) :+ envelope :+ allowedCommand)*)
    )

  private val str = Json.obj("type" -> Json.fromString("string"))
  private val int = Json.obj("type" -> Json.fromString("integer"))
  private val strArray = Json.obj("type" -> Json.fromString("array"), "items" -> str)

  private def ref(name: String): Json = Json.obj("$ref" -> Json.fromString(s"#/$$defs/$name"))

  private def anyOf(names: Seq[String]): Json = Json.obj("anyOf" -> Json.arr(names.map(ref)*))

  /** Ein Knoten mit Diskriminator "type" als const (kein oneOf, kein discriminator). */
  private def obj(tpe: String, fields: (String, Json)*): (String, Json) =
    tpe -> closed((("type" -> Json.obj("const" -> Json.fromString(tpe))) +: fields)*)

  private def closed(fields: (String, Json)*): Json = Json.obj(
    "type" -> Json.fromString("object"),
    "properties" -> Json.obj(fields*),
    "required" -> Json.arr(fields.map(f => Json.fromString(f._1))*),
    "additionalProperties" -> Json.False
  )
