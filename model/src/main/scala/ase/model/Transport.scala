package ase.model

import cats.effect.Sync
import cats.syntax.all.*

import java.net.URI
import java.nio.file.Path
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Antwort einer Modell-API, roh. retryAfter aus dem Header `retry-after` (Sekunden). */
final case class HttpReply(status: Int, retryAfter: Option[FiniteDuration], body: String)

/** Der eine HTTP-Aufruf eines Adapters. Tests setzen eine Fälschung ein; der echte Transport ist JdkTransport. */
trait Transport[F[_]]:
  /** Left: keine Antwort (Verbindung, Zeitüberschreitung). */
  def post(body: String): F[Either[String, HttpReply]]

/** java.net.http (Schritt 4: keine weitere Abhängigkeit). headers: was der Anbieter verlangt, bei Anthropic mit dem
  * Schlüssel aus der Umgebung, nie aus einer Datei.
  */
final class JdkTransport[F[_]: Sync](endpoint: URI, headers: List[(String, String)], timeout: FiniteDuration) extends Transport[F]:
  private val client = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(30)).build()

  def post(body: String): F[Either[String, HttpReply]] =
    Sync[F].blocking {
      val builder = HttpRequest
        .newBuilder(endpoint)
        .timeout(JDuration.ofMillis(timeout.toMillis))
        .header("content-type", "application/json")
      headers.foreach((k, v) => builder.header(k, v))
      val request = builder.POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build()
      try
        val r = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        val retryAfter = Option(r.headers().firstValue("retry-after").orElse(null)).flatMap(_.trim.toIntOption).map(_.seconds)
        Right(HttpReply(r.statusCode(), retryAfter, r.body()))
      catch case NonFatal(e) => Left(e.getClass.getSimpleName + Option(e.getMessage).fold("")(": " + _))
    }

object JdkTransport:
  /** Transport für den Anbieter der Konfiguration. Anthropic: Schlüssel aus config.apiKeyEnv, fehlt er, ein
    * typisierter Fehler. Ollama: lokal, ohne Schlüssel. Agy und ClaudeCode: ein Prozess statt HTTP (D#54, D#58).
    * forbidden: Verzeichnisse, unter denen das Programm einer CLI nicht liegen darf und deren PATH-Einträge es nicht
    * sieht, mindestens das Arbeitsverzeichnis (D#93). HTTP nur über https, außer zum eigenen Rechner.
    */
  def open[F[_]: Sync](config: ModelConfig, forbidden: List[Path]): F[Either[ModelError, Transport[F]]] =
    ModelConfig.checkEndpoint(config) match
      case Left(reason) => Sync[F].pure(Left(ModelError.Endpoint(reason)))
      case Right(()) =>
        config.provider match
          case Provider.Ollama =>
            Sync[F].pure(Right(JdkTransport[F](URI.create(config.endpoint), Nil, config.requestTimeout)))
          case Provider.Agy =>
            cli(config, forbidden)((c, env) => AgyTransport[F](c, env))
          case Provider.ClaudeCode =>
            cli(config, forbidden)((c, env) => ClaudeCodeTransport[F](c, env))
          case Provider.Anthropic =>
            Sync[F].delay(sys.env.get(config.apiKeyEnv).filter(_.nonEmpty)).map {
              case None => Left(ModelError.MissingApiKey(config.apiKeyEnv))
              case Some(key) =>
                Right(JdkTransport[F](URI.create(config.endpoint), List("x-api-key" -> key, "anthropic-version" -> config.apiVersion), config.requestTimeout))
            }

  /** Das Programm einmal auflösen, der Transport startet dann den absoluten Pfad. */
  private def cli[F[_]: Sync](config: ModelConfig, forbidden: List[Path])(
      make: (ModelConfig, Map[String, String]) => Transport[F]
  ): F[Either[ModelError, Transport[F]]] =
    Sync[F].blocking {
      val env = sys.env
      CliProcess.locate(config.endpoint, env, forbidden).left.map(ModelError.Endpoint(_)).map(exe =>
        make(config.copy(endpoint = exe.toString), CliProcess.environment(env, forbidden))
      )
    }
