package tw.idv.idiotech.ghostspeak.daqiaotou

import SpotKeeper.*
import com.typesafe.scalalogging.LazyLogging
import io.circe.{ Codec, Decoder, Encoder, Json, Printer }
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }

import tw.idv.idiotech.ghostspeak.agent.{
  Action,
  Actuator,
  EventBase,
  FcmSender,
  Forward,
  Message,
  PersistFailure,
  Scenario,
  Sensor,
  Session,
  SystemPayload
}
import tw.idv.idiotech.ghostspeak.agent.Actuator.Command.Perform
import tw.idv.idiotech.ghostspeak.agent.FreshDefault
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command
import tw.idv.idiotech.ghostspeak.agent.SystemPayload.Join

import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

class SpotKeeper(sensor: Sensor[SpotPayload], actuator: Actuator[Content, SpotPayload])
    extends LazyLogging {

  type Command = Sensor.Command[SpotPayload]

  def onEvent(state: State, event: Event): State = event match {
    case u: Event.Uploaded =>
      State(u.spot :: state.spots.filterNot(_.id == u.spot.id))
  }

  def onCommand(
    user: String,
    scenarioId: String,
    sensor: ActorRef[Command],
    actuator: ActorRef[Actuator.Command[Content]]
  )(state: State, command: Command): Effect[Event, State] =
    command match {
      case Command.Sense(message, replyTo) =>
        message.payload match {
          case Left(Join) =>
            sensor ! Command.Broadcast(
              Message[SpotPayload](
                UUID.randomUUID().toString,
                None,
                "?u",
                user,
                Right(SpotPayload.Dump(user)),
                scenarioId
              )
            )
            Effect.none
          case Right(SpotPayload.Upload(spot)) =>
            sensor ! Command.Broadcast(
              Message[SpotPayload](
                UUID.randomUUID().toString,
                None,
                "?u",
                user,
                Right(SpotPayload.Show(spot)),
                scenarioId
              ),
              None
            )
            Effect.persist(Event.Uploaded(spot))
          case Right(SpotPayload.Show(spot)) =>
            spot
              .toActions(user, Session(scenarioId, None))
              .foreach(a => actuator ! Perform(a, System.currentTimeMillis()))
            Effect.none
          case Right(SpotPayload.Dump(newUser)) =>
            state.spots
              .flatMap(_.toActions(newUser, Session(scenarioId, None)))
              .foreach(a => actuator ! Perform(a, System.currentTimeMillis()))
            Effect.none
          case _ => Effect.none
        }
      // the staff page asks every engine; spots keep no variables, and the asker must not wait for a timeout
      case Command.Inspect(_, _, replyTo) =>
        Effect.reply(replyTo)(
          StatusReply.success(UserVariables(Map.empty, Map.empty).asJson.noSpaces)
        )
      case _ => Effect.none
    }

  def ub(
    userScenario: Scenario,
    scenarioId: String,
    sensorRef: ActorRef[Command],
    actuator: ActorRef[Actuator.Command[Content]]
  ) =
    Behaviors.setup[Command] { ctx =>
      val user = userScenario.id
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(s"spot-$user"),
        emptyState = State(Nil),
        commandHandler = onCommand(user, scenarioId, sensorRef, actuator),
        eventHandler = onEvent
      ).onPersistFailure(PersistFailure.restart)
    }

  def createUserScenario(
    scenarioId: String,
    sensorRef: ActorRef[Command],
    actuatorRef: ActorRef[Actuator.Command[Content]]
  )(
    actorContext: ActorContext[?],
    created: Sensor.Event.Created
  ): Either[String, ActorRef[Command]] =
    Right(
      actorContext.spawn(
        ub(created.scenario, scenarioId, sensorRef, actuatorRef),
        created.scenario.id
      )
    )

  def sendMessage(
    action: Action[Content]
  )(using actorSystem: ActorSystem[?], encoder: Encoder[Action[Content]]): Future[Done] = {
    val actionJson = action.asJson.printWith(Printer.spaces2)
    val redisKey = s"action-${action.session.scenario}-${action.receiver}"
    val hashKey = action.id
    logger.debug(s"saving action to redis: $redisKey $hashKey")
    onRedis(_.hset(redisKey, hashKey, actionJson))
      .flatMap(_ => FcmSender.send(action))(using actorSystem.executionContext)
  }

  /** The sensor holding the scenarios, each a per-user sensor that is its child. */
  def rootSensor(
    sensorRef: ActorRef[Command],
    actuatorRef: ActorRef[Actuator.Command[Content]]
  ): Behavior[Command] =
    sensor.apply(
      "spotkeeper",
      (ctx, created) =>
        if (created.scenario.engine == "spotkeeper")
          Try {
            val scn = created.scenario
            val actor: Behavior[Sensor.Command[SpotPayload]] =
              sensor.apply(
                scn.id,
                createUserScenario(created.scenario.id, sensorRef, actuatorRef),
                sensor.onCommandPerUser,
                Sensor.PerUserSnapshotEvery
              )
            ctx.spawn(
              actor,
              if (created.uniqueId.nonEmpty) created.uniqueId else UUID.randomUUID().toString
            )
          }
            .fold(
              e => {
                e.printStackTrace()
                Left(s"invalid template: ${e.getMessage}")
              },
              Right(_)
            )
        else Left("not a graphscript scenario")
    )

  // The actuator and the root sensor are siblings under this actor, which forwards to the
  // sensor: a sensor restarting after a journal failure stops its own children, and the
  // actuator must not be one of them.
  val spotKeeperBehavior: Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      val sensorRef: ActorRef[Command] = ctx.self
      val actuatorBehavior = Behaviors.setup[Actuator.Command[Content]] { actx =>
        given system: ActorSystem[?] = ctx.system
        actuator.behaviorFromFuture("spotkeeper", sensorRef, sendMessage)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] =
        ctx.spawn(actuatorBehavior, s"actuator-${UUID.randomUUID().toString}")
      // nothing reaches the players without it: if it stops, so does this actor (and the server)
      ctx.watch(actuatorRef)
      Forward.toChild(ctx, rootSensor(sensorRef, actuatorRef), "sensor")
    }
}

object SpotKeeper {

  case class Spot(
    id: String = s"SPOT-${UUID.randomUUID()}",
    title: String,
    text: String,
    images: List[String],
    location: Location
  ) {

    def toActions(user: String, session: Session): List[Action[Content]] = List(
      Action[Content](
        s"$id-marker-pending",
        user,
        "ghost",
        Content(
          Task.Marker(
            location,
            config.icon.pending,
            title,
            None
          ),
          Condition.Always
        ),
        session
      ),
      Action[Content](
        s"$id-popup",
        user,
        "ghost",
        Content(
          Task.Popup(
            Some(text.trim).filter(_.nonEmpty),
            List("關閉"),
            false,
            images,
            Set(Destination.Alert, Destination.Notification)
          ),
          Condition.Geofence(location, 14)
        ),
        session
      ),
      Action[Content](
        s"$id-marker-arrived",
        user,
        "ghost",
        Content(
          Task.Marker(
            location,
            config.icon.arrived,
            title,
            Some(s"$id-popup")
          ),
          Condition.Geofence(location, 14)
        ),
        session
      )
    )
  }

  object Spot {

    // a Spot uploaded without an id gets a new one, as the constructor does
    given codecForSpot: Codec.AsObject[Spot] =
      FreshDefault(ConfiguredCodec.derived[Spot], "id")(
        Json.fromString(s"SPOT-${UUID.randomUUID()}")
      )
  }

  sealed trait SpotPayload derives ConfiguredCodec

  // need to maintain an outer instance
  object SpotPayload {
    // broadcast image to every user
    case class Upload(spot: Spot) extends SpotPayload
    case class Show(spot: Spot) extends SpotPayload
    case class Dump(user: String) extends SpotPayload
  }

  case class State(spots: List[Spot]) extends EventBase derives ConfiguredCodec

  sealed trait Event extends EventBase derives ConfiguredCodec

  object Event {
    case class Uploaded(spot: Spot) extends Event
  }
}
