package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior, PostStop }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.Http

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class Server {

  sealed trait Message
  private final case class StartFailed(cause: Throwable) extends Message
  private final case class Started(binding: ServerBinding) extends Message
  case object Stop extends Message

  def apply(scenarioManager: ScenarioManager, host: String, port: Int): Behavior[Message] = Behaviors.setup { ctx =>
    implicit val system = ctx.system

    val dummySensor = ctx.spawn(Sensor(scenarioManager), "DummySensor")
    val routes = new EventRoutes(dummySensor)

    val serverBinding: Future[Http.ServerBinding] =
      Http().newServerAt(host, port).bind(routes.theEventRoutes)
    ctx.pipeToSelf(serverBinding) {
      case Success(binding) => Started(binding)
      case Failure(ex)      => StartFailed(ex)
    }

    def running(binding: ServerBinding): Behavior[Message] =
      Behaviors
        .receiveMessagePartial[Message] {
          case Stop =>
            ctx.log.info(
              "Stopping server http://{}:{}/",
              binding.localAddress.getHostString,
              binding.localAddress.getPort
            )
            Behaviors.stopped
        }
        .receiveSignal {
          case (_, PostStop) =>
            binding.unbind()
            Behaviors.same
        }

    def starting(wasStopped: Boolean): Behaviors.Receive[Message] =
      Behaviors.receiveMessage[Message] {
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
