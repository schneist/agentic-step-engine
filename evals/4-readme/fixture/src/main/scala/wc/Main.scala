package wc

import scala.io.Source

val allModes = Set("--lines", "--words", "--chars")

@main def run(args: String*): Unit =
  val (flags, files) = args.partition(_.startsWith("--"))
  val modes = if flags.isEmpty then allModes else flags.toSet
  val unknown = modes -- allModes
  if unknown.nonEmpty then
    Console.err.println(s"Unbekannte Option: ${unknown.mkString(", ")}")
    sys.exit(2)
  for file <- files do
    val c = Counter.count(Source.fromFile(file).mkString)
    val parts = List(
      Option.when(modes("--lines"))(s"${c.lines} Zeilen"),
      Option.when(modes("--words"))(s"${c.words} Wörter"),
      Option.when(modes("--chars"))(s"${c.chars} Zeichen")
    ).flatten
    println(s"$file: ${parts.mkString(", ")}")
