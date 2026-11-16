package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.{ Codec, Decoder, Json }
import io.circe.parser.{ decode, parse }
import io.circe.syntax.*
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{ schedulerFromActorSystem, Askable }
import org.apache.pekko.http.scaladsl.model.{ ContentTypes, HttpEntity, StatusCodes }
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.StatusReply

import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.{ CategoryManager, Scenario, Sensor }
import tw.idv.idiotech.ghostspeak.agent.Sensor.Identifier

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](
  sensor: ActorRef[Sensor.Command[T]],
  cm: ActorRef[CategoryManager.Command],
  system: ActorSystem[?]
) extends agent.EventRoutes[T](sensor, cm, system) {

  import EventRoutes.*

  override lazy val routes: List[Route] = List(
    pathPrefix("v1" / "scenario" / Segment / Segment / "user" / Segment / "actions") {
      (engine, scenarioId, userId) =>
        val ret: Route =
          delete {
            val fetch: Future[List[Json]] =
              onRedis(PendingActions.take(_, scenarioId, userId)).map { stored =>
                val actions = PendingActions.readable(stored)
                actions.foreach { json =>
                  json
                    .as[Action]
                    .fold(
                      error => logger.error(s"failed to parse action ${json.noSpaces}", error),
                      a => PerformanceLogger.insert(userId, a.id, "redis")
                    )
                }
                actions
              }
            onComplete(fetch) {
              // in the order they were performed: a step's chat lines are shown as they come
              case Success(actions) => complete(StatusCodes.OK, List(), actions.asJson)
              case Failure(StatusReply.ErrorMessage(reason)) =>
                complete(StatusCodes.InternalServerError -> reason)
              case Failure(e) =>
                complete(StatusCodes.InternalServerError -> e.getMessage)
            }
          }
        ret
    },
    // a player's story variables, for the staff page; answered by the player's actor
    pathPrefix("v1" / "scenario" / Segment / Segment / "user" / Segment / "variables") {
      (_, scenarioId, userId) =>
        get {
          onComplete(
            sensor.askWithStatus[String](x => Sensor.Command.Inspect[T](scenarioId, userId, x))
          ) {
            case Success(json) =>
              complete(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, json))
            // a player the scenario has not seen yet has no variables; a 404 would read as a
            // missing route on the phone
            case Failure(StatusReply.ErrorMessage(Sensor.NoSuchUser)) =>
              complete(
                StatusCodes.OK,
                HttpEntity(
                  ContentTypes.`application/json`,
                  UserVariables(Map.empty, Map.empty).asJson.noSpaces
                )
              )
            case Failure(StatusReply.ErrorMessage(reason @ Sensor.NoSuchScenario)) =>
              complete(StatusCodes.NotFound -> reason)
            case Failure(StatusReply.ErrorMessage(reason)) =>
              complete(StatusCodes.InternalServerError -> reason)
            case Failure(e) =>
              complete(StatusCodes.InternalServerError -> e.getMessage)
          }
        }
    },
    pathPrefix("v1" / "scenario" / Segment / Segment / "resources") { (engine, scenarioId) =>
      get {
        logger.debug(s"############ HTTP GET: /v1/scenario/$engine/$scenarioId/resources")
        onComplete(
          sensor.askWithStatus[String](x =>
            Sensor.Command.Query[T](None, None, None, Some(Identifier(engine, scenarioId)), None, x)
          )
        ) {
          case Success(msg) =>
            logger.debug(
              s"############ Finished HTTP GET: /v1/scenario/$engine/$scenarioId/resources"
            )
            parse(msg).fold(
              e => complete(StatusCodes.InternalServerError -> e.getMessage),
              j =>
                summon[Decoder[List[Scenario]]]
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
                            List(),
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

  case class Resources(images: List[String], sounds: List[String]) derives Codec.AsObject

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
                clearDialog,
                self
              ) =>
            r.copy(images = pictures ++ r.images)
          case Task.Sound(url, volumeSetting, mode)           => r.copy(sounds = url :: r.sounds)
          case Task.Marker(location, icon, title, actionId)   => r.copy(images = icon :: r.images)
          case Task.MarkerRemoval(id)                         => r
          case Task.PopupDismissal(places)                    => r
          case Task.IncomingCall(caller, status, portrait)    => r
          case Task.MapStyle(url, satellite)                  => r
          case Task.IntroImage(bgUrl, _, _, _, _, _, _, _, _) => r
          case Task.ButtonStyle(bgColor, textColor)           => r
          case Task.PopupStyle(alertTextColor, _)             => r
          case Task.VariableUpdates(_) | VariableUpdate(_, _, _) => r
          case Task.EndGame                                      => r
          case Task.GuideImage(_)                                => r
          case Task.GuideImageRemoval                            => r
          case Task.Silence(_, _)                                => r
          case Task.Friend(_, _, _)                              => r
        }
      )
    }
  }
}
