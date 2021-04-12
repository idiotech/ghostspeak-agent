package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

object Sensor {
  sealed trait Command[P]

  case class Sense[P](message: Message[P], replyTo: Option[ActorRef[StatusReply[String]]] = None)
      extends Command[P]

  case class Create[P](scenario: Scenario, replyTo: ActorRef[StatusReply[String]])
      extends Command[P]

  case class Destroy[P](scenarioId: String, replyTo: ActorRef[StatusReply[String]])
      extends Command[P]

  sealed trait Event[P]
  case class Destroyed[P](scenarioId: String) extends Event[P]
  case class Created[P](scenario: Scenario) extends Event[P]

  type State[P] = Map[String, Created[P]]

  type CreatorScenarioActor[P] = (
    ActorContext[_],
    Scenario
  ) => Option[ActorRef[Command[P]]]

  type Reff[P] = ReplyEffect[Event[P], State[P]]

  implicit class RichActorContext(val ctx: ActorContext[_]) extends AnyVal {
    def getChild[P](name: String) = ctx.child(name).map(_.asInstanceOf[ActorRef[Command[P]]])
  }

  def onCommand[P](
    ctx: ActorContext[Command[P]],
    createChild: CreatorScenarioActor[P]
  )(state: State[P], cmd: Command[P]): Reff[P] = cmd match {
    case Sense(message, replyTo) =>
      val reply: StatusReply[String] =
        ctx
          .getChild[P](message.scenarioId)
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
        createChild(ctx, scenario)
          .fold[Reff[P]] {
            Effect.reply(replyTo)(StatusReply.Error("no such engine"))
          } { actor =>
            Effect
              .persist(Created[P](scenario))
              .thenReply(replyTo)(_ => StatusReply.Success("created"))
          }
      else Effect.reply(replyTo)(StatusReply.Error("already exists"))
    case Destroy(id, replyTo) =>
      if (state.contains(id))
        Effect
          .persist[Event[P], State[P]](Destroyed[P](id))
          .thenReply(replyTo)(_ => StatusReply.Success("destroying"))
      else
        Effect.reply(replyTo)(StatusReply.Error("already exists"))
  }

  type OnCommand[P] = (ActorContext[Command[P]], CreatorScenarioActor[P]) => (
    State[P],
    Command[P]
  ) => Reff[P]

  def onEvent[P](state: State[P], evt: Event[P]): State[P] = evt match {
    case c: Created[P] =>
      state + (c.scenario.id -> c)
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
          val scns = state.values.map(s => createScenario(context, s.scenario))
          println(s"====== recovery complete!")
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
        val maybeActor = existingActor.orElse {
          create(ctx, scenario)
        }
        maybeActor.foreach(_ ! Sense(message))
        if (existingActor.isEmpty) {
          maybeActor.fold[Reff[P]](Effect.noReply) { s =>
            Effect.persist(Created[P](scenario)).thenNoReply()
          }
        } else Effect.noReply
      case _ => general(state, cmd)
    }
  }

  def perUser[P](name: String, createScenarioPerUser: Scenario => CreatorScenarioActor[P]) =
    apply[P](
      name,
      { (ctx, scn) =>
        val actor = apply[P](scn.id, createScenarioPerUser(scn), onCommandPerUser[P])
        Some(ctx.spawn(actor, scn.id))
      }
    )

}
