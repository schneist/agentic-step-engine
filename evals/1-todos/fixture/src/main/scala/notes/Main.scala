package notes

// TODO: Kommandozeilenargumente statt fester Datei
object Main:
  def main(args: Array[String]): Unit =
    val store = Store.open("notes.db")
    val marker = "TODO"
    println(s"${store.count} Notizen, Marker $marker")
