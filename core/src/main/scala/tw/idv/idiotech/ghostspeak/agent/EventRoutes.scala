package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import akka.pattern.StatusReply
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import io.circe.Json
import io.circe.parser.parse

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](sensor: ActorRef[Sensor.Command[T]], system: ActorSystem[_])
    extends FailFastCirceSupport
    with LazyLogging {

  import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
  import akka.actor.typed.scaladsl.AskPattern.Askable
  implicit val jsonStreamingSupport = EntityStreamingSupport.json()
  implicit val decoder = Message.decoder[T]
  implicit val ac = system
  import Sensor.Command._

  // asking someone requires a timeout and a scheduler, if the timeout hits without response
  // the ask is failed with a TimeoutException
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.executionContext

  def routes: List[Route] = List(
    pathPrefix("v1" / "event") {
      concat(
        pathEnd {
          concat(
            post {
              entity(as[Message[T]]) { message =>
                onComplete(sensor.askWithStatus[String](x => Sense[T](message, Some(x)))) {
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
    pathPrefix("v1" / "broadcast") {
      concat(
        pathEnd {
          concat(
            post {
              entity(as[Message[T]]) { message =>
                onComplete(sensor.askWithStatus[String](x => Broadcast[T](message, Some(x)))) {
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
      parameters("overwrite".optional, "name".optional) { (overwriteParam, name) =>
        val overwrite = overwriteParam.fold(false)(_ == "true")
        put {
          entity(as[Json]) { template =>
            val ret: Route = onComplete {
              val deletion = if (overwrite) {
                logger.info("overwriting!")
                sensor.askWithStatus[String](x => Destroy[T](scenarioId, x)).recover { case e =>
                  logger.error("failed to delete scanario", e)
                }
              } else Future.unit
              deletion.flatMap(_ =>
                sensor.askWithStatus[String](x =>
                  Create[T](Scenario(scenarioId, engine, template.toString, name), x)
                )
              )
            } {
              case Success(msg) => complete(msg)
              case Failure(StatusReply.ErrorMessage(reason)) =>
                complete(StatusCodes.InternalServerError -> reason)
              case Failure(e) =>
                complete(StatusCodes.InternalServerError -> e.getMessage)
            }
            ret
          }
        }
      }
    },
    pathPrefix("v1" / "scenario") {
      get {
        onComplete(sensor.askWithStatus[String](x => Query[T](x))) {
          case Success(msg) =>
            parse(msg).fold(
              e => complete(StatusCodes.InternalServerError -> e.getMessage),
              j =>
                complete(
                  StatusCodes.OK,
                  List(`Content-Type`(`application/json`)), {
                    val ret =
                      j.mapArray(_.map(_.mapObject(_.remove("template").remove("engine"))))
                    ret
                  }
                )
            )
          case Failure(StatusReply.ErrorMessage(reason)) =>
            complete(StatusCodes.InternalServerError -> reason)
          case Failure(e) =>
            complete(StatusCodes.InternalServerError -> e.getMessage)
        }
      }
    }
  )

  lazy val theEventRoutes: Route = cors() {
    concat(routes: _*)
  }
}
