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
import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.parser.decode
import io.circe.syntax._
import tw.idv.idiotech.ghostspeak.agent.EventRoutes.ScenarioPayload
import tw.idv.idiotech.ghostspeak.agent.Sensor.Identifier

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](
  sensor: ActorRef[Sensor.Command[T]],
  categoryManager: ActorRef[CategoryManager.Command],
  system: ActorSystem[_]
) extends FailFastCirceSupport
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

  private def getScenarios(
    q: ActorRef[StatusReply[String]] => Query[T],
    selector: Scenario => Json
  ) =
    get {
      onComplete(sensor.askWithStatus[String](q)) {
        case Success(msg) =>
          decode[List[Scenario]](msg).fold(
            e => complete(StatusCodes.InternalServerError -> e.getMessage),
            scenarios =>
              complete(
                StatusCodes.OK,
                List(`Content-Type`(`application/json`)),
                scenarios
                  .map { s =>
                    val m = s.metadata
                    val m1 = m.copy(
                      name = m.name orElse s.name,
                      displayName = m.displayName orElse s.displayName,
                      owner = m.owner orElse s.owner
                    )
                    s.copy(metadata = m1)
                  }
                  .map(selector)
                  .asJson
              )
          )
        case Failure(StatusReply.ErrorMessage(reason)) =>
          complete(StatusCodes.InternalServerError -> reason)
        case Failure(e) =>
          complete(StatusCodes.InternalServerError -> e.getMessage)
      }
    }

  def routes: List[Route] = List(
    pathPrefix("v1" / "event") {
      concat(
        pathEnd {
          concat(
            post {
              entity(as[Message[T]]) { message =>
                logger.info(s"############ HTTP POST: /v1/event with content $message")
                onComplete(sensor.askWithStatus[String](x => Sense[T](message, Some(x)))) {
                  case Success(msg) =>
                    logger.info(s"############ Finished HTTP POST: /v1/event")
                    complete(msg)
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
                logger.info(s"############ HTTP POST: /v1/broadcast")
                onComplete(sensor.askWithStatus[String](x => Broadcast[T](message, Some(x)))) {
                  case Success(msg) =>
                    logger.info(s"############ Finished HTTP POST: /v1/broadcast")
                    complete(msg)
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
      parameters(
        "overwrite".optional
      ) { overwriteParam =>
        val overwrite = overwriteParam.fold(false)(_ == "true")
        put {
          entity(as[ScenarioPayload]) { payload =>
            logger.info(
              s"############ HTTP PUT: /v1/scenario/$engine/$scenarioId?overwrite=$overwriteParam"
            )
            val ret: Route = onComplete {
              val deletion = if (overwrite) {
                logger.info("overwriting!")
                sensor.askWithStatus[String](x => Destroy[T](scenarioId, x)).recover { case e =>
                  logger.error("failed to delete scenario", e)
                }
              } else Future.unit
              deletion.flatMap { _ =>
                sensor.askWithStatus[String](x =>
                  Create[T](
                    Scenario(
                      scenarioId,
                      engine,
                      payload.template.toString,
                      payload.metadata.name,
                      payload.metadata.displayName.filter(d => d != "null" && d != "undefined"),
                      payload.metadata.public,
                      payload.metadata.owner,
                      payload.metadata.ordinal,
                      payload.metadata
                    ),
                    x
                  )
                )
              }
            } {
              case Success(msg) =>
                logger.info(
                  s"############ Finished HTTP PUT: /v1/scenario/$engine/$scenarioId?overwrite=$overwriteParam"
                )
                complete(msg)
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
    pathPrefix("v1" / "scenario" / Segment / Segment) { (engine, scenarioId) =>
      delete {
        logger.info(s"############ HTTP DELETE: /v1/scenario/$engine/$scenarioId")
        val ret: Route = onComplete {
          sensor.askWithStatus[String](x => Destroy[T](scenarioId, x)).recover { case e =>
            logger.error("failed to delete scenario", e)
          }
        } {
          case Success(msg) =>
            logger.info(s"############ Finished HTTP DELETE: /v1/scenario/$engine/$scenarioId")
            complete("DELETED")
          case Failure(StatusReply.ErrorMessage(reason)) =>
            complete(StatusCodes.InternalServerError -> reason)
          case Failure(e) =>
            complete(StatusCodes.InternalServerError -> e.getMessage)
        }
        ret
      }
    },
    pathPrefix("v1" / "scenario" / Segment / Segment) { (engine, scenarioId) =>
      get {
        getScenarios(x => Query[T](None, None, None, Some(Identifier(engine, scenarioId)), x), _.asJson)
      }
    },
    pathPrefix("v1" / "scenario") {
      parameters("public".optional, "category".optional, "featured".optional) { (`public`, category, featured) =>
        val isPublic = `public`.map(_.toBoolean)
        val isFeatured = `featured`.map(_.toBoolean)
        getScenarios(
          x => Query[T](isPublic, isFeatured, category, None, x),
          _.asJson.mapObject(_.remove("template").remove("engine"))
        )
      }
    },
    pathPrefix("v1" / "category") {
      parameters("public".optional) { `public` =>
        get {
          val isPublic = `public`.map(_.toBoolean)
          onComplete(
            categoryManager.askWithStatus[CategoryManager.State](x =>
              CategoryManager.Command.GetAll(x)
            )
          ) {
            case Success(state) =>
              complete(
                StatusCodes.OK,
                List(`Content-Type`(`application/json`)),
                CategoryManager.State(
                  state.categories.sortBy(_.order)
                )
              )
            case Failure(StatusReply.ErrorMessage(reason)) =>
              complete(StatusCodes.InternalServerError -> reason)
            case Failure(e) =>
              complete(StatusCodes.InternalServerError -> e.getMessage)
          }
        }
      }
    },
    pathPrefix("v1" / "category") {
      concat(
        pathEnd {
          concat(
            put {
              entity(as[CategoryManager.State]) { state =>
                onComplete(
                  categoryManager.askWithStatus[String](x =>
                    CategoryManager.Command.Upload(state, x)
                  )
                ) {
                  case Success(msg) =>
                    complete(msg)
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
    }
  )

  lazy val theEventRoutes: Route = cors() {
    concat(routes: _*)
  }
}

object EventRoutes {

  @ConfiguredJsonCodec
  case class ScenarioPayload(template: Json, metadata: Metadata)
}
