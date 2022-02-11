package tw.idv.idiotech.ghostspeak.daqiaotou

import cats.implicits._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.server.Route
import io.circe.{ Decoder, Json, ParsingFailure }
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.{ Scenario, Sensor }
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.pattern.StatusReply
import io.circe.parser.{ decode, parse }
import io.circe.syntax._
import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import io.circe.generic.JsonCodec

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](sensor: ActorRef[Sensor.Command[T]], system: ActorSystem[_])
    extends agent.EventRoutes[T](sensor, system) {

  import EventRoutes._

  override lazy val routes: List[Route] = List(
    pathPrefix("v1" / "scenario" / Segment / Segment / "user" / Segment / "actions") {
      (engine, scenarioId, userId) =>
        val key = s"action-$scenarioId-$userId"
        logger.info(s"getting action from redis: $key")
        delete {
          val fetch: Future[Map[String, String]] = for {
            res <- Future(redis.withClient(c => c.hgetall[String, String](key)))
            all <- {
              logger.info(s"getting action from redis: ${res.getOrElse(Map.empty)}")
              Future.successful(res.getOrElse(Map.empty))
            }
            _ <- Future.traverse(all.keys)(actionId =>
              Future(redis.withClient(c => c.hdel(key, actionId)))
            )
          } yield all
          onComplete(fetch) {
            case Success(all) =>
              val parseResults: Either[ParsingFailure, Json] =
                all.values.map(parse).toList.sequence.map(_.asJson)
              parseResults.fold(
                e => complete(StatusCodes.InternalServerError -> e.getMessage()),
                actions =>
                  complete(
                    StatusCodes.OK,
                    List(`Content-Type`(`application/json`)),
                    actions
                  )
              )
            case Failure(StatusReply.ErrorMessage(reason)) =>
              complete(StatusCodes.InternalServerError -> reason)
            case Failure(e) =>
              complete(StatusCodes.InternalServerError -> e.getMessage)
          }
        }

    },
    pathPrefix("v1" / "scenario" / Segment / Segment / "resources") { (engine, scenarioId) =>
      get {
        onComplete(sensor.askWithStatus[String](x => Sensor.Command.Query[T](x))) {
          case Success(msg) =>
            parse(msg).fold(
              e => complete(StatusCodes.InternalServerError -> e.getMessage),
              j =>
                implicitly[Decoder[List[Scenario]]]
                  .decodeJson(j)
                  .fold(
                    e => complete(StatusCodes.InternalServerError -> s"invalid scenario: $e $j"),
                    _.find(s => s.id == scenarioId && s.engine == engine).fold(
                      complete(StatusCodes.NotFound -> "")
                    ) { scn =>
                      decode[List[GraphScript.Node]](scn.template).fold(
                        e => complete(StatusCodes.InternalServerError -> s"invalid template: $e"),
                        nodes =>
                          complete(
                            StatusCodes.OK,
                            List(`Content-Type`(`application/json`)),
                            Resources(nodes).asJson
                          )
                      )
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
  ) ++ super.routes
}

object EventRoutes {

  @JsonCodec
  case class Resources(images: List[String], sounds: List[String])

  object Resources {

    def apply(nodes: List[GraphScript.Node]): Resources = {
      val tasks: List[Task] = nodes.flatMap(_.performances.map(_.action.content.task))
      tasks.foldRight(Resources(Nil, Nil))((t, r) =>
        t match {
          case Task.Popup(text, choices, allowTextReply, pictures, destinations) =>
            r.copy(images = pictures ++ r.images)
          case Task.Sound(url, volumeSetting, mode)         => r.copy(sounds = url :: r.sounds)
          case Task.Marker(location, icon, title, actionId) => r.copy(images = icon :: r.images)
          case Task.MarkerRemoval(id)                       => r
          case Task.PopupDismissal(places)                  => r
          case Task.IncomingCall(caller, status, portrait)  => r
          case Task.MapStyle(url)                           => r
        }
      )
    }
  }
}
