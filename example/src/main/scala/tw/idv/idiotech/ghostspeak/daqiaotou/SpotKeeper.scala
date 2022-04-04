package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.Done
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.syntax._
import io.circe.{ Decoder, Encoder, Printer }
import tw.idv.idiotech.ghostspeak.agent.Actuator.Command.Perform
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command
import tw.idv.idiotech.ghostspeak.agent.SystemPayload.Join
import tw.idv.idiotech.ghostspeak.agent.{
  Action,
  Actuator,
  EventBase,
  FcmSender,
  Message,
  Scenario,
  Sensor,
  Session,
  SystemPayload
}

import java.util.UUID
import scala.concurrent.Future
import scala.util.Try
import SpotKeeper._

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
      )
    }

  def createUserScenario(
    scenarioId: String,
    sensorRef: ActorRef[Command],
    actuatorRef: ActorRef[Actuator.Command[Content]]
  )(
    actorContext: ActorContext[_],
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
  )(implicit actorSystem: ActorSystem[_], encoder: Encoder[Action[Content]]): Future[Done] = {
    val actionJson = action.asJson.printWith(Printer.spaces2)
    val redisKey = s"action-${action.session.scenario}-${action.receiver}"
    val hashKey = action.id
    logger.info(s"saving action to redis: $redisKey $hashKey")
    redis.withClient(r => r.hset(redisKey, hashKey, actionJson))
    FcmSender.send(action)
  }

  val spotKeeperBehavior: Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      val sensorRef: ActorRef[Command] = ctx.self
      val actuatorBehavior = Behaviors.setup[Actuator.Command[Content]] { actx =>
        implicit val system = ctx.system

        def fcm(root: ActorRef[Actuator.Command[Content]]) =
          actuator.fromFuture(sendMessage, root)

        def discover(
          c: ActorContext[_],
          a: Action[Content],
          r: ActorRef[Actuator.Command[Content]]
        ) = Some {
          c.spawnAnonymous(fcm(actx.self))
        }
        actuator.behavior("spotkeeper", discover, sensorRef)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] =
        ctx.spawn(actuatorBehavior, s"actuator-${UUID.randomUUID().toString}")
      actuatorRef ! Actuator.Command.Timeout() // for recovery
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
                  sensor.onCommandPerUser
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
    }

}

object SpotKeeper {

  @ConfiguredJsonCodec
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

  @ConfiguredJsonCodec
  sealed trait SpotPayload

  // need to maintain an outer instance
  object SpotPayload {
    // broadcast image to every user
    case class Upload(spot: Spot) extends SpotPayload
    case class Show(spot: Spot) extends SpotPayload
    case class Dump(user: String) extends SpotPayload
  }

  @ConfiguredJsonCodec
  case class State(spots: List[Spot]) extends EventBase

  @ConfiguredJsonCodec
  sealed trait Event extends EventBase

  object Event {
    case class Uploaded(spot: Spot) extends Event
  }

}
