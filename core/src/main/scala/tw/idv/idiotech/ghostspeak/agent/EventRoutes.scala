package tw.idv.idiotech.ghostspeak.agent

import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport
import com.typesafe.scalalogging.LazyLogging
import io.circe.{ Codec, Decoder, Json }
import io.circe.derivation.ConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax.*
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import org.apache.pekko.http.scaladsl.model.{ HttpMethods, StatusCodes }
import org.apache.pekko.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import org.apache.pekko.http.scaladsl.server.{ Directive1, Route, StandardRoute }
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import tw.idv.idiotech.ghostspeak.agent.EventRoutes.{ ScenarioPayload, StaffCode }
import tw.idv.idiotech.ghostspeak.agent.Sensor.Identifier

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration.*
import scala.util.{ Failure, Success }

class EventRoutes[T: Decoder](
  sensor: ActorRef[Sensor.Command[T]],
  categoryManager: ActorRef[CategoryManager.Command],
  system: ActorSystem[?],
  staffAuth: StaffAuth = StaffAuth.fromConfig()
) extends FailFastCirceSupport
    with LazyLogging {

  import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
  import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
  given jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
  given decoder: Decoder[Message[T]] = Message.decoder[T]
  given ac: ActorSystem[?] = system
  import Sensor.Command.*

  // asking someone requires a timeout and a scheduler, if the timeout hits without response
  // the ask is failed with a TimeoutException
  given timeout: Timeout = 3.seconds
  given ec: ExecutionContextExecutor = system.executionContext

  // true for a valid staff token, false when none is sent; any other credential is refused, so
  // that staff with a stale token see an error rather than a list missing unpublished stories
  private val staffAccess: Directive1[Boolean] =
    optionalHeaderValueByType(Authorization).flatMap {
      case None => provide(false)
      case Some(Authorization(OAuth2BearerToken(token))) if staffAuth.isValid(token) =>
        provide(true)
      case Some(_) =>
        StandardRoute.toDirective[Tuple1[Boolean]](
          complete(StatusCodes.Unauthorized -> "invalid or expired staff token")
        )
    }

  // story passcodes are for staff only; players type them, they are never handed out
  private def withoutPasscode(staff: Boolean)(json: Json): Json =
    if (staff) json
    else json.hcursor.downField("metadata").downField("passcode").delete.top.getOrElse(json)

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
                List(),
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
                logger.debug(s"############ HTTP POST: /v1/event with content $message")
                onComplete(sensor.askWithStatus[String](x => Sense[T](message, Some(x)))) {
                  case Success(msg) =>
                    logger.debug(s"############ Finished HTTP POST: /v1/event")
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
            logger.debug(
              s"############ HTTP PUT: /v1/scenario/$engine/$scenarioId?overwrite=$overwriteParam"
            )
            val ret: Route = onComplete {
              val deletion = if (overwrite) {
                logger.debug("overwriting!")
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
                logger.debug(
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
        logger.debug(s"############ HTTP DELETE: /v1/scenario/$engine/$scenarioId")
        val ret: Route = onComplete {
          sensor.askWithStatus[String](x => Destroy[T](scenarioId, x)).recover { case e =>
            logger.error("failed to delete scenario", e)
          }
        } {
          case Success(msg) =>
            logger.debug(s"############ Finished HTTP DELETE: /v1/scenario/$engine/$scenarioId")
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
        staffAccess { staff =>
          getScenarios(
            x => Query[T](None, None, None, Some(Identifier(engine, scenarioId)), None, x),
            s => withoutPasscode(staff)(s.asJson)
          )
        }
      }
    },
    pathPrefix("v1" / "scenario") {
      parameters("public".optional, "category".optional, "featured".optional, "passcode".optional) {
        (`public`, category, featured, passcode) =>
          staffAccess { staff =>
            // without a staff token only published stories are listed, unless a passcode
            // picks out a story
            val isPublic =
              if (staff || passcode.isDefined) `public`.map(_.toBoolean) else Some(true)
            val isFeatured = `featured`.map(_.toBoolean)
            getScenarios(
              x => Query[T](isPublic, isFeatured, category, None, passcode, x),
              s => withoutPasscode(staff)(s.asJson.mapObject(_.remove("template").remove("engine")))
            )
          }
      }
    },
    pathPrefix("v1" / "staff" / "session") {
      pathEnd {
        post {
          entity(as[StaffCode]) { body =>
            staffAuth
              .issue(body.code)
              .fold(complete(StatusCodes.Unauthorized -> "wrong staff code"))(t =>
                complete(StatusCodes.OK, List(), t.asJson)
              )
          }
        }
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
                List(),
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

  cors()

  lazy val corsSettings = CorsSettings(system.classicSystem)
    .withAllowedMethods(Seq(HttpMethods.GET, HttpMethods.PUT, HttpMethods.DELETE, HttpMethods.POST))
    .withAllowedOrigins(HttpOriginMatcher.Default(agentConfig.corsDomainList))

  lazy val theEventRoutes: Route = cors(corsSettings) {
    concat(routes*)
  }
}

object EventRoutes {

  case class ScenarioPayload(template: Json, metadata: Metadata) derives ConfiguredCodec

  case class StaffCode(code: String) derives Codec.AsObject
}
