package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.syntax._

import java.util.UUID

object Sensor {
  sealed trait Command[P]

  case class Sense[P](message: Message[P], replyTo: Option[ActorRef[StatusReply[String]]] = None)
      extends Command[P]

  case class Create[P](scenario: Scenario, replyTo: ActorRef[StatusReply[String]])
      extends Command[P]

  case class Query[P](replyTo: ActorRef[StatusReply[String]]) extends Command[P]

  case class Destroy[P](scenarioId: String, replyTo: ActorRef[StatusReply[String]])
      extends Command[P]

  sealed trait Event[P]
  case class Destroyed[P](scenarioId: String) extends Event[P]

  case class Created[P](scenario: Scenario, uniqueId: String = UUID.randomUUID().toString)
      extends Event[P]

  type State[P] = Map[String, Created[P]]

  type CreatorScenarioActor[P] = (
    ActorContext[_],
    Created[P]
  ) => Either[String, ActorRef[Command[P]]]

  type Reff[P] = ReplyEffect[Event[P], State[P]]

  implicit class RichActorContext(val ctx: ActorContext[_]) extends AnyVal {
    def getChild[P](name: String) = ctx.child(name).map(_.asInstanceOf[ActorRef[Command[P]]])
  }

  def onCommand[P](
    ctx: ActorContext[Command[P]],
    createChild: CreatorScenarioActor[P]
  )(state: State[P], cmd: Command[P]): Reff[P] = cmd match {
    case Sense(message, replyTo) =>
//      println(s"state = $state")
      val id = state.get(message.scenarioId).map(_.uniqueId).getOrElse("invalid")
      println(s"id = $id, test = ${ctx.children}")
      val reply: StatusReply[String] =
        ctx
          .getChild[P](id)
          .fold[StatusReply[String]](
            StatusReply.Error("no such scenario")
          ) { actor =>
            actor ! Sense(message)
            StatusReply.success("OK")
          }
      replyTo.fold[Reff[P]] {
        Effect.noReply
      } { r =>
        Effect.reply[StatusReply[String], Event[P], State[P]](r)(reply)
      }
    case Create(scenario, replyTo) =>
      if (!state.contains(scenario.id))
        createChild(ctx, Created(scenario, ""))
          .fold[Reff[P]](
            e => Effect.reply(replyTo)(StatusReply.Error(s"invalid scenario: $e")),
            actor =>
              Effect
                .persist(Created[P](scenario, actor.path.name))
                .thenReply(replyTo)(_ => StatusReply.Success("created"))
          )
      else Effect.reply(replyTo)(StatusReply.Error("already exists"))
    case Destroy(id, replyTo) =>
      state
        .get(id)
        .fold[ReplyEffect[Event[P], State[P]]] {
          Effect.reply(replyTo)(StatusReply.Error("doesn't exist"))
        } { created =>
          ctx.child(created.uniqueId).foreach { a =>
            println(s"stopping $a")
            ctx.stop(a)
          }
          Effect
            .persist[Event[P], State[P]](Destroyed[P](id))
            .thenReply(replyTo)(_ => StatusReply.Success("destroying"))
        }
    case Query(replyTo) =>
      Effect.reply(replyTo)(
        StatusReply.success(state.values.map(_.scenario).toList.asJson.toString())
      )
  }

  type OnCommand[P] = (ActorContext[Command[P]], CreatorScenarioActor[P]) => (
    State[P],
    Command[P]
  ) => Reff[P]

  def onEvent[P](state: State[P], evt: Event[P]): State[P] = evt match {
    case c: Created[P] =>
      state + (c.scenario.id -> c)
    case d: Destroyed[P] =>
      state - d.scenarioId
  }

  def apply[P](
    name: String,
    createScenario: CreatorScenarioActor[P],
    handle: OnCommand[P] = onCommand[P] _
  ): Behavior[Command[P]] =
    Behaviors.setup { context: ActorContext[Command[P]] =>
      EventSourcedBehavior
        .withEnforcedReplies[Command[P], Event[P], State[P]](
          persistenceId = PersistenceId.ofUniqueId(s"sensor-$name"),
          emptyState = Map.empty,
          commandHandler = handle(context, createScenario),
          eventHandler = onEvent
        )
        .receiveSignal { case (state, RecoveryCompleted) =>
          val scns = state.values.map(s => createScenario(context, s))
          println(s"====== recovery complete for $name")
          scns.foreach(println)

        }
    }

  def onCommandPerUser[P](
    ctx: ActorContext[Command[P]],
    create: CreatorScenarioActor[P]
  )(state: State[P], cmd: Command[P]): Reff[P] = {
    val general = onCommand[P](ctx, create) _
    cmd match {
      case Sense(message, _) =>
        val scenario = Scenario(message.sender, message.scenarioId, "")
        val existingActor = ctx.getChild[P](message.sender)
        val maybeActor = existingActor.toRight("no such user").orElse {
          create(ctx, Created(scenario, ""))
        }
        maybeActor.foreach(_ ! Sense(message))
        if (existingActor.isEmpty) {
          maybeActor.fold[Reff[P]](
            e => Effect.noReply,
            s => Effect.persist(Created[P](scenario)).thenNoReply()
          )
        } else Effect.noReply
      case _ => general(state, cmd)
    }
  }
}
