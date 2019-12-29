package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

class Agent(memory: Memory, beliefRevisers: List[Behavior[Message]], actuator: Behavior[Message]) {

  val observer: Behavior[Message] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage { message =>
        memory.remember(message)
        beliefRevisers.foreach {
          r => {
            val reviser = context.spawn(r, "reviser")
            reviser ! message.copy(sender = context.self)
          }
        }
        message match {
          case Message(_, "intend", payload) =>
            // remember: is doing it, have done it
            val actuator = context.spawn(actuator, "actuator")
            actuator ! message
          case _ =>
        }
        Behaviors.same
      }
    }

  val system: ActorSystem[Message] =
    ActorSystem(observer, "hello")

  def perceive(event: Message) = system ! event
}
