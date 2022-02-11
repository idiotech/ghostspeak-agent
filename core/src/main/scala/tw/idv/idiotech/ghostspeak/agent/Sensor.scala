package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import com.typesafe.scalalogging.LazyLogging
import io.circe.{ Decoder, Encoder }
import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.syntax._
import tw.idv.idiotech.ghostspeak.agent.Sensor.Event.Created

import java.util.UUID

class Sensor[P: Encoder: Decoder] extends LazyLogging {

  import Sensor.Command
  import Sensor.Command._

  import Sensor.Event
  import Sensor.Event._
  import Sensor.State

  type CreatorScenarioActor = (
    ActorContext[_],
    Created
  ) => Either[String, ActorRef[Command[P]]]

  type Reff = ReplyEffect[Event, State]

  def getChild(ctx: ActorContext[_], name: String) =
    ctx.child(name).map(_.asInstanceOf[ActorRef[Command[P]]])

  def getChildren(ctx: ActorContext[_]): Iterable[ActorRef[Command[P]]] =
    ctx.children.map(_.asInstanceOf[ActorRef[Command[P]]])

  def onCommand(
    ctx: ActorContext[Command[P]],
    createChild: CreatorScenarioActor
  )(state: State, cmd: Command[P]): Reff = cmd match {
    case Sense(message, replyTo) =>
      val id = state.scenarios.get(message.scenarioId).map(_.uniqueId).getOrElse("invalid")
      val reply: StatusReply[String] =
        getChild(ctx, id)
          .fold[StatusReply[String]](
            StatusReply.Error("no such scenario")
          ) { actor =>
            actor ! Sense(message)
            StatusReply.success("OK")
          }
      replyTo.fold[Reff] {
        Effect.noReply
      } { r =>
        Effect.reply[StatusReply[String], Event, State](r)(reply)
      }
    case Broadcast(message, replyTo) =>
      val id = state.scenarios.get(message.scenarioId).map(_.uniqueId).getOrElse("invalid")
      val reply: StatusReply[String] =
        getChild(ctx, id)
          .fold[StatusReply[String]](
            StatusReply.Error("no such scenario")
          ) { actor =>
            actor ! Broadcast(message)
            StatusReply.success("OK")
          }
      replyTo.fold[Reff] {
        Effect.noReply
      } { r =>
        Effect.reply[StatusReply[String], Event, State](r)(reply)
      }
    case Create(scenario, replyTo) =>
      if (!state.scenarios.contains(scenario.id))
        createChild(ctx, Created(scenario, ""))
          .fold[Reff](
            e => Effect.reply(replyTo)(StatusReply.Error(s"invalid scenario: $e")),
            actor =>
              Effect
                .persist(Created(scenario, actor.path.name))
                .thenReply(replyTo)(_ => StatusReply.Success("created"))
          )
      else Effect.reply(replyTo)(StatusReply.Error("already exists"))
    case Destroy(id, replyTo) =>
      state.scenarios
        .get(id)
        .fold[ReplyEffect[Event, State]] {
          Effect.reply(replyTo)(StatusReply.Error("doesn't exist"))
        } { created =>
          ctx.child(created.uniqueId).foreach { a =>
            logger.info(s"stopping $a")
            ctx.stop(a)
          }
          Effect
            .persist[Event, State](Destroyed(id))
            .thenReply(replyTo)(_ => StatusReply.Success("destroying"))
        }
    case Query(replyTo) =>
      Effect.reply(replyTo)(
        StatusReply.success(state.scenarios.values.map(_.scenario).toList.asJson.toString())
      )
  }

  type OnCommand = (ActorContext[Command[P]], CreatorScenarioActor) => (
    State,
    Command[P]
  ) => Reff

  def onEvent(state: State, evt: Event): State = evt match {
    case c: Created =>
      State(state.scenarios + (c.scenario.id -> c))
    case d: Destroyed =>
      State(state.scenarios - d.scenarioId)
  }

  def apply(
    name: String,
    createScenario: CreatorScenarioActor,
    handle: OnCommand = onCommand _
  ): Behavior[Command[P]] =
    Behaviors.setup { context: ActorContext[Command[P]] =>
      EventSourcedBehavior
        .withEnforcedReplies[Command[P], Event, State](
          persistenceId = PersistenceId.ofUniqueId(s"sensor-$name"),
          emptyState = State(),
          commandHandler = handle(context, createScenario),
          eventHandler = onEvent
        )
        .receiveSignal { case (state, RecoveryCompleted) =>
          val scns = state.scenarios.values.map(s => createScenario(context, s))
          logger.info(s"====== recovery complete for $name")
          scns.foreach(println)

        }
    }

  def onCommandPerUser(
    ctx: ActorContext[Command[P]],
    create: CreatorScenarioActor
  )(state: State, cmd: Command[P]): Reff = {
    val general = onCommand(ctx, create) _
    cmd match {
      case Sense(message, _) =>
        val scenario = Scenario(message.sender, message.scenarioId, "")
        val existingActor = getChild(ctx, message.sender)
        val maybeActor = existingActor.toRight("no such user").orElse {
          create(ctx, Created(scenario, ""))
        }
        maybeActor.foreach(_ ! Sense(message))
        if (existingActor.isEmpty) {
          maybeActor.fold[Reff](
            e => Effect.noReply,
            s => Effect.persist(Created(scenario)).thenNoReply()
          )
        } else Effect.noReply
      case Broadcast(message, _) =>
        val children: Iterable[ActorRef[Command[P]]] = getChildren(ctx)
        children.foreach(c =>
          c ! Sense(
            message.copy(
              receiver = c.path.name
            )
          )
        )
        Effect.noReply
      case _ => general(state, cmd)
    }
  }
}

object Sensor {

  @ConfiguredJsonCodec
  sealed trait Event extends EventBase

  object Event {
    case class Destroyed(scenarioId: String) extends Event

    @ConfiguredJsonCodec
    case class Created(scenario: Scenario, uniqueId: String = UUID.randomUUID().toString)
        extends Event
  }

  @ConfiguredJsonCodec
  case class State(scenarios: Map[String, Created] = Map.empty) extends EventBase

  sealed trait Command[P] extends CommandBase

  object Command {

    case class Sense[P](message: Message[P], replyTo: Option[ActorRef[StatusReply[String]]] = None)
        extends Command[P]

    case class Broadcast[P](
      message: Message[P],
      replyTo: Option[ActorRef[StatusReply[String]]] = None
    ) extends Command[P]

    case class Create[P](scenario: Scenario, replyTo: ActorRef[StatusReply[String]])
        extends Command[P]

    case class Query[P](replyTo: ActorRef[StatusReply[String]]) extends Command[P]

    case class Destroy[P](scenarioId: String, replyTo: ActorRef[StatusReply[String]])
        extends Command[P]
  }
}
