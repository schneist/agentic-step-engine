package wc

final case class Counts(lines: Int, words: Int, chars: Int)

object Counter:
  def count(text: String): Counts =
    Counts(
      lines = text.linesIterator.size,
      words = text.split("""\s+""").count(_.nonEmpty),
      chars = text.length
    )
