package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import akka.pattern.StatusReply
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.circe.Decoder
import io.circe.Json
import io.circe.parser.parse

import scala.concurrent.Future
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

  lazy val theEventRoutes: Route = cors() {
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
        parameters("overwrite".optional, "name".optional) { (overwriteParam, name) =>
          val overwrite = overwriteParam.fold(false)(_ == "true")
          put {
            entity(as[Json]) { template =>
              val ret: Route = onComplete {
                val deletion = if (overwrite) {
                  println("overwriting!")
                  sensor.askWithStatus[String](x => Sensor.Destroy[T](scenarioId, x)).recover {
                    case e => println(e)
                  }
                } else Future.unit
                deletion.flatMap(_ =>
                  sensor.askWithStatus[String](x =>
                    Sensor.Create[T](Scenario(scenarioId, engine, template.toString, name), x)
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
          onComplete(sensor.askWithStatus[String](x => Sensor.Query[T](x))) {
            case Success(msg) =>
              parse(msg).fold(
                e => complete(StatusCodes.InternalServerError -> e.getMessage),
                j =>
                  complete(
                    StatusCodes.OK,
                    List(`Content-Type`(`application/json`)), {
                      println(s"original: $j")
                      val ret =
                        j.mapArray(_.map(_.mapObject(_.remove("template").remove("engine"))))
                      println(s"modified: $ret")
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
  }
}
