package tw.idv.idiotech.ghostspeak.daqiaotou

import cats.implicits._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.server.Route
import io.circe.{ Decoder, Json, ParsingFailure }
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.{ Scenario, Sensor }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.pattern.StatusReply
import io.circe.parser.{ decode, parse }
import io.circe.syntax._
import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import io.circe.generic.JsonCodec

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](sensor: ActorRef[Sensor.Command[T]], system: ActorSystem[_])
    extends agent.EventRoutes[T](sensor, system) {

  import EventRoutes._

  override lazy val routes: List[Route] = List(
    pathPrefix("v1" / "scenario" / Segment / Segment / "user" / Segment / "actions") {
      (engine, scenarioId, userId) =>
        val key = s"action-$scenarioId-$userId"
        delete {
          logger.info(
            s"############ HTTP DELETE: /v1/scenario/$engine/$scenarioId/user/$userId/actions"
          )
          val fetch: Future[Map[String, String]] = for {
            res <- Future(redis.withClient(c => c.hgetall[String, String](key)))
            all <- {
              logger.info(s"getting action from redis: ${res.getOrElse(Map.empty)}")
              res.getOrElse(Map.empty).values.foreach { s =>
                decode[Action](s).fold(
                  error => logger.error(s"failed to parse action $s", error),
                  a => PerformanceLogger.insert(userId, a.id, "redis")
                )
              }
              Future.successful(res.getOrElse(Map.empty))
            }
            _ <- Future.traverse(all.keys)(actionId =>
              Future(redis.withClient(c => c.hdel(key, actionId)))
            )
          } yield all
          onComplete(fetch) {
            case Success(all) =>
              logger.info(
                s"############ Finished HTTP DELETE: /v1/scenario/$engine/$scenarioId/user/$userId/actions"
              )
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
        logger.info(s"############ HTTP GET: /v1/scenario/$engine/$scenarioId/resources")
        onComplete(sensor.askWithStatus[String](x => Sensor.Command.Query[T](x))) {
          case Success(msg) =>
            logger.info(
              s"############ Finished HTTP GET: /v1/scenario/$engine/$scenarioId/resources"
            )
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
    },
    pathPrefix("v1" / "perf-log") {
      concat(
        pathEnd {
          concat(
            post {
              entity(as[PerfLog]) { perfLog =>
                val result = Future {
                  PerformanceLogger.insert(perfLog.user, perfLog.action, perfLog.stage)
                }
                val ret: Route = onComplete(result) {
                  case Success(all) =>
                    complete(StatusCodes.OK)
                  case Failure(StatusReply.ErrorMessage(reason)) =>
                    complete(StatusCodes.InternalServerError -> reason)
                  case Failure(e) =>
                    complete(StatusCodes.InternalServerError -> e.getMessage)
                }
                ret
              }
            }
          )
        }
      )
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
          case Task.Popup(
                text,
                choices,
                allowTextReply,
                pictures,
                destinations,
                closeAlertAfterReply,
                clearDialog
              ) =>
            r.copy(images = pictures ++ r.images)
          case Task.Sound(url, volumeSetting, mode)         => r.copy(sounds = url :: r.sounds)
          case Task.Marker(location, icon, title, actionId) => r.copy(images = icon :: r.images)
          case Task.MarkerRemoval(id)                       => r
          case Task.PopupDismissal(places)                  => r
          case Task.IncomingCall(caller, status, portrait)  => r
          case Task.MapStyle(url, satellite)                => r
          case Task.IntroImage(bgUrl, _, _, _, _, _)        => r
          case Task.ButtonStyle(bgColor, textColor)         => r
          case Task.VariableUpdates(_)                      => r
          case Task.EndGame                                 => r
          case Task.GuideImage(_)                           => r
          case Task.GuideImageRemoval                       => r
          case Task.Silence(_, _)                           => r
        }
      )
    }
  }
}
