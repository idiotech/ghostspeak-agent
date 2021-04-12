package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import akka.pattern.StatusReply
import io.circe.Decoder
import io.circe.Json

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](sensor: ActorRef[Sensor.Command[T]])(implicit
  system: ActorSystem[_]
) extends FailFastCirceSupport {

  import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
  import akka.actor.typed.scaladsl.AskPattern.Askable
  implicit val jsonStreamingSupport = EntityStreamingSupport.json()
  implicit val decoder = Message.decoder[T]

  // asking someone requires a timeout and a scheduler, if the timeout hits without response
  // the ask is failed with a TimeoutException
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.executionContext

  lazy val theEventRoutes: Route =
    concat(
      pathPrefix("v1" / "event") {
        concat(
          pathEnd {
            concat(
              post {
                entity(as[Message[T]]) { message =>
                  onComplete(sensor.askWithStatus[String](x => Sensor.Sense[T](message, Some(x)))) {
                    case Success(msg) => complete(msg)
                    case Failure(StatusReply.ErrorMessage(reason)) =>
                      complete(StatusCodes.InternalServerError -> reason)
                    case Failure(e) =>
                      complete(StatusCodes.InternalServerError -> e.getMessage)
                  }
                }
              }
            )
          }
        )
      },
      pathPrefix("v1" / "scenario" / Segment / Segment) { (engine, scenarioId) =>
        put {
          entity(as[Json]) { template =>
            onComplete(
              sensor.askWithStatus[String](x =>
                Sensor.Create[T](Scenario(scenarioId, engine, template.toString), x)
              )
            ) {
              case Success(msg) => complete(msg)
              case Failure(StatusReply.ErrorMessage(reason)) =>
                complete(StatusCodes.InternalServerError -> reason)
              case Failure(e) =>
                complete(StatusCodes.InternalServerError -> e.getMessage)
            }
          }
        }
      }
    )
}
