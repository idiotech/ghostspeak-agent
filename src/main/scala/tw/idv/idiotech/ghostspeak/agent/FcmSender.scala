package tw.idv.idiotech.ghostspeak.agent

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import akka.stream.alpakka.google.firebase.fcm.scaladsl.GoogleFcm
import akka.stream.alpakka.google.firebase.fcm._
import akka.stream.RestartSettings
import akka.stream.alpakka.google.firebase.fcm.FcmNotificationModels.Token
import akka.stream.scaladsl.{ RestartFlow, Sink, Source }
import io.circe.generic.extras.{ Configuration, ConfiguredJsonCodec }
import io.circe.syntax._
import io.circe.parser.decode

import scala.concurrent.Future
import scala.util.{ Failure, Success }

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

  sealed trait Command
  case class Perform(action: Action) extends Command
  case object OK extends Command
  case class KO(reason: String) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    implicit val system: ActorSystem[Nothing] = ctx.system
    Behaviors.receiveMessage {
      case Perform(action) =>
        val future = send(action)
        ctx.pipeToSelf(future) {
          case Success(_) => OK
          case Failure(e) => KO(e.getMessage)
        }
        Behaviors.same
      case OK =>
        Behaviors.stopped
      case KO =>
        Behaviors.stopped
    }
  }

  def send(action: Action)(implicit actorSystem: ActorSystem[_]): Future[Done] =
    Source
      .single(
        FcmNotification.empty
          .withData(
            Map("payload" -> action.asJson.toString())
          )
          .withTarget(Token(action.receiver))
      )
      .via(RestartFlow.onFailuresWithBackoff(settings)(_ => GoogleFcm.send(fcmConfig)))
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
