package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import org.apache.pekko.stream.connectors.google.firebase.fcm.scaladsl.GoogleFcm
import org.apache.pekko.stream.connectors.google.firebase.fcm._
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.connectors.google.firebase.fcm.FcmNotificationModels.Token
import org.apache.pekko.stream.scaladsl.{ RestartFlow, Sink, Source }
import io.circe.Encoder
import io.circe.generic.extras.{ Configuration, ConfiguredJsonCodec }
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
    scala.io.Source.fromFile(ConfigFactory.load().getString("fcm.key-file")).mkString
  ).fold(throw _, _.toFcmSettngs)

  val settings = RestartSettings(
    minBackoff = 1.seconds,
    maxBackoff = 30.seconds,
    randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
  ).withMaxRestarts(20, 5.minutes)

  def send[T](
    action: Action[T],
    logPerf: (Action[T], String) => Unit = (_: Action[T], _: String) => ()
  )(implicit actorSystem: ActorSystem[_], encoder: Encoder[Action[T]]): Future[Done] =
    Source
      .single {
        logger.info(s"sending action: ${action.asJson.toString()} to receiver: ${action.receiver}")
        FcmNotification.empty
          .withData(
            Map("action" -> action.asJson.toString())
          )
          .withTarget(Token(action.receiver))
      }
      .via(RestartFlow.onFailuresWithBackoff(settings)(() => GoogleFcm.send(fcmConfig)))
      .map {
        case res @ FcmSuccessResponse(name) =>
          logger.info(s"Successful $name")
          logPerf(action, "fcm_sent")
          res
        case res @ FcmErrorResponse(errorMessage) =>
          logger.info(s"Send error $errorMessage $fcmConfig")
          logPerf(action, "fcm_error")
          res
      }
      .runWith(Sink.ignore)
}
