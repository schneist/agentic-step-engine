package ase.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}
import io.circe.Json

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object TestSupport:
  /** Sucht eine Datei des Repos vom Arbeitsverzeichnis aufwärts (flows/, evals/). */
  def repoFile(relative: String): String =
    Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.exists(_))
      .map(Files.readString)
      .getOrElse(throw new IllegalStateException(s"$relative nicht gefunden"))

  /** Policies wie im Runner für Eval 6 und den Release-Flow: Standard plus ./run-tests.sh. */
  val config: CheckConfig = CheckConfig.default.copy(argPolicies = ArgPolicy.defaults :+ ArgPolicy.script("./run-tests.sh"))

  private val mapper = new ObjectMapper()
  private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
  private val schemas = collection.concurrent.TrieMap.empty[Int, com.networknt.schema.JsonSchema]

  /** Fehlermeldungen des Validators; leer heißt gültig. */
  def schemaErrors(nesting: Int, instance: Json): List[String] =
    val schema = schemas.getOrElseUpdate(nesting, factory.getSchema(Schema.termSchema(nesting).noSpaces))
    schema.validate(mapper.readTree(instance.noSpaces)).asScala.toList.map(_.getMessage)

  def stamp(t: Term, o: Origin): Term = t match
    case Term.ReadFile(p, _)          => Term.ReadFile(p, o)
    case Term.ListDir(p, _)           => Term.ListDir(p, o)
    case Term.WriteFile(p, c, _)      => Term.WriteFile(p, c, o)
    case Term.EditFile(p, a, b, _)    => Term.EditFile(p, a, b, o)
    case Term.MoveFile(a, b, _)       => Term.MoveFile(a, b, o)
    case Term.Shell(c, a, _)          => Term.Shell(c, a, o)
    case Term.Ask(q, _)               => Term.Ask(q, o)
    case Term.Done(s, _)              => Term.Done(s, o)
    case Term.Hole(g, e, _, v)        => Term.Hole(g, e, o, v)
    case Term.Seq(s, _)               => Term.Seq(s.map(stamp(_, o)), o)
    case Term.If(p, a, b, _)          => Term.If(p, stamp(a, o), stamp(b, o), o)
    case Term.RepeatUntil(b, u, m, _) => Term.RepeatUntil(stamp(b, o), u, m, o)

  def nestedSeq(depth: Int): Term =
    (1 to depth).foldLeft[Term](Term.Done("innen", Origin.Human))((t, _) => Term.Seq(List(t), Origin.Human))
