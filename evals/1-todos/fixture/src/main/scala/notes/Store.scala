package notes

final class Store private (path: String):
  // TODO: Datei sperren, bevor geschrieben wird
  def count: Int = 0

  /* TODO: Index nach Datum */
  def add(text: String): Unit = ()

object Store:
  def open(path: String): Store = new Store(path) // TODO Fehler, wenn Datei fehlt
