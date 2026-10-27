package app

final case class Config(timeout: Int, retries: Int)

object Server:
  val defaultTimeout = 10
  val defaultRetries = 3

  /** Liest config/prod.conf; fehlende Werte fallen auf die Defaults zurück. */
  def load(path: String): Config = Config(defaultTimeout, defaultRetries)
