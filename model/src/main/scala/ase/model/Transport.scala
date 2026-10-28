package ase.model

import cats.effect.Sync
import cats.syntax.all.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Antwort der Messages API, roh. retryAfter aus dem Header `retry-after` (Sekunden). */
final case class HttpReply(status: Int, retryAfter: Option[FiniteDuration], body: String)

/** Der eine HTTP-Aufruf des Adapters. Tests setzen eine Fälschung ein; der echte Transport ist JdkTransport. */
trait Transport[F[_]]:
  /** Left: keine Antwort (Verbindung, Zeitüberschreitung). */
  def post(body: String): F[Either[String, HttpReply]]

/** java.net.http (Schritt 4: keine weitere Abhängigkeit). Der Schlüssel kommt aus der Umgebung, nie aus einer Datei. */
final class JdkTransport[F[_]: Sync](endpoint: URI, apiKey: String, apiVersion: String, timeout: FiniteDuration) extends Transport[F]:
  private val client = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(30)).build()

  def post(body: String): F[Either[String, HttpReply]] =
    Sync[F].blocking {
      val request = HttpRequest
        .newBuilder(endpoint)
        .timeout(JDuration.ofMillis(timeout.toMillis))
        .header("content-type", "application/json")
        .header("x-api-key", apiKey)
        .header("anthropic-version", apiVersion)
        .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
        .build()
      try
        val r = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        val retryAfter = Option(r.headers().firstValue("retry-after").orElse(null)).flatMap(_.trim.toIntOption).map(_.seconds)
        Right(HttpReply(r.statusCode(), retryAfter, r.body()))
      catch case NonFatal(e) => Left(e.getClass.getSimpleName + Option(e.getMessage).fold("")(": " + _))
    }

object JdkTransport:
  /** Der Schlüssel aus ANTHROPIC_API_KEY; fehlt er, ein typisierter Fehler. */
  def fromEnv[F[_]: Sync](config: ModelConfig): F[Either[ModelError, Transport[F]]] =
    Sync[F].delay(sys.env.get(config.apiKeyEnv).filter(_.nonEmpty)).map {
      case None      => Left(ModelError.MissingApiKey(config.apiKeyEnv))
      case Some(key) => Right(JdkTransport[F](URI.create(config.endpoint), key, config.apiVersion, config.requestTimeout))
    }
