package tw.idv.idiotech.ghostspeak.agent

import akka.Done
import akka.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import akka.stream.alpakka.google.firebase.fcm.scaladsl.GoogleFcm
import akka.stream.alpakka.google.firebase.fcm._
import akka.stream.RestartSettings
import akka.stream.alpakka.google.firebase.fcm.FcmNotificationModels.Token
import akka.stream.scaladsl.{RestartFlow, Sink, Source}
import io.circe.Encoder
import io.circe.generic.extras.{Configuration, ConfiguredJsonCodec}
import io.circe.syntax._
import io.circe.parser.decode

import scala.concurrent.Future

object FcmSender extends LazyLogging {
  private implicit val configuration = Configuration.default.withSnakeCaseMemberNames

  @ConfiguredJsonCodec
  case class FcmConfig(projectId: String, privateKey: String, clientEmail: String) {
    def toFcmSettngs = FcmSettings(clientEmail, privateKey, projectId)
  }

  val fcmConfig = decode[FcmConfig](
    scala.io.Source.fromResource(ConfigFactory.load().getString("fcm.key-file")).mkString
  ).fold(throw _, _.toFcmSettngs)

  val settings = RestartSettings(
    minBackoff = 1.seconds,
    maxBackoff = 30.seconds,
    randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
  ).withMaxRestarts(20, 5.minutes)

  def send[T](action: Action[T])(implicit actorSystem: ActorSystem[_], encoder: Encoder[Action[T]]): Future[Done] =
    Source
      .single(
        FcmNotification.empty
          .withData(
            Map("payload" -> action.asJson.toString())
          )
          .withTarget(Token(action.receiver))
      )
      .via(RestartFlow.onFailuresWithBackoff(settings)(() => GoogleFcm.send(fcmConfig)))
      .map {
        case res @ FcmSuccessResponse(name) =>
          println(s"Successful $name")
          res
        case res @ FcmErrorResponse(errorMessage) =>
          println(s"Send error $errorMessage")
          res
      }
      .runWith(Sink.ignore)
}
