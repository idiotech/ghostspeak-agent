package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior, PostStop }
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import io.circe.Decoder

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.{ Failure, Success }

object Server {

  sealed trait Msg extends CommandBase

  private final case class StartFailed(cause: Throwable) extends Msg
  private final case class Started(binding: ServerBinding) extends Msg
  case object Stop extends Msg

  def apply[P: Decoder](
    behavior: Behavior[Sensor.Command[P]],
    routing: (
      ActorRef[Sensor.Command[P]],
      ActorRef[CategoryManager.Command],
      ActorSystem[_]
    ) => EventRoutes[P],
    host: String,
    port: Int
  ): Behavior[Msg] =
    Behaviors.setup { ctx =>
      implicit val system = ctx.system
      Await.result(SchemaUtils.createIfNotExists(), Duration.Inf)
      val dummySensor: ActorRef[Sensor.Command[P]] = ctx.spawn(behavior, "RootSensor")
      val categoryManager: ActorRef[CategoryManager.Command] =
        ctx.spawn(CategoryManager(), "CategoryManager")
      val routes = routing(dummySensor, categoryManager, system)

      val serverBinding: Future[Http.ServerBinding] =
        Http().newServerAt(host, port).bind(routes.theEventRoutes)
      ctx.pipeToSelf(serverBinding) {
        case Success(binding) => Started(binding)
        case Failure(ex)      => StartFailed(ex)
      }

      def running(binding: ServerBinding): Behavior[Msg] =
        Behaviors
          .receiveMessagePartial[Msg] { case Stop =>
            ctx.log.info(
              "Stopping server http://{}:{}/",
              binding.localAddress.getHostString,
              binding.localAddress.getPort
            )
            Behaviors.stopped
          }
          .receiveSignal { case (_, PostStop) =>
            binding.unbind()
            Behaviors.same
          }

      def starting(wasStopped: Boolean): Behaviors.Receive[Msg] =
        Behaviors.receiveMessage[Msg] {
          case StartFailed(cause) =>
            throw new RuntimeException("Server failed to start", cause)
          case Started(binding) =>
            ctx.log.info(
              "Server online at http://{}:{}/",
              binding.localAddress.getHostString,
              binding.localAddress.getPort
            )
            if (wasStopped) ctx.self ! Stop
            running(binding)
          case Stop =>
            // we got a stop message but haven't completed starting yet,
            // we cannot stop until starting has completed
            starting(wasStopped = true)
        }

      starting(wasStopped = false)
    }

//  def main(args: Array[String]): Unit = {
//    val system: ActorSystem[Message] =
//      ActorSystem(Server("localhost", 8080), "BuildJobsServer")
//  }
}
