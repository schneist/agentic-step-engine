package ase.model

import cats.effect.Sync
import cats.syntax.all.*

import java.net.URI
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
    * typisierter Fehler. Ollama: lokal, ohne Schlüssel. Agy: ein Prozess statt HTTP (D#54).
    */
  def open[F[_]: Sync](config: ModelConfig): F[Either[ModelError, Transport[F]]] =
    config.provider match
      case Provider.Ollama =>
        Sync[F].pure(Right(JdkTransport[F](URI.create(config.endpoint), Nil, config.requestTimeout)))
      case Provider.Agy =>
        Sync[F].pure(Right(AgyTransport[F](config)))
      case Provider.Anthropic =>
        Sync[F].delay(sys.env.get(config.apiKeyEnv).filter(_.nonEmpty)).map {
          case None => Left(ModelError.MissingApiKey(config.apiKeyEnv))
          case Some(key) =>
            Right(JdkTransport[F](URI.create(config.endpoint), List("x-api-key" -> key, "anthropic-version" -> config.apiVersion), config.requestTimeout))
        }
