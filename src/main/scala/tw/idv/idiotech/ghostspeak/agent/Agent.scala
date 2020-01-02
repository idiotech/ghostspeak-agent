package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.IO
import cats.syntax._

class Agent(memory: Memory, beliefRevisers: List[Behavior[Message]], actuator: Behavior[Message]) {

  def memoryBehavior(memoryIO: IO[Memory]): Behavior[Message] =
    Behaviors.receiveMessage[Message] { event =>
      memoryBehavior(for {
        mem    <- memoryIO
        newMem <- mem.remember(event)
      } yield newMem)
    }

  val observer: Behavior[Message] =
    Behaviors.setup { context =>
      {
        val memoryActor = context.spawn(memoryBehavior(IO(memory)), "memory")
        Behaviors.receiveMessage { message =>
          /*

        for {
          memory <- mem.remember
          updatedMemory <- beliefUpdates
          action <- plan(memory)
          memory: sequential queued update
          _ <- act(action)
        }

           */
          memoryActor ! message
          beliefRevisers.foreach { r =>
            {
              val reviser = context.spawn(r, "reviser")
              reviser ! message.copy(sender = context.self)
            }
          }
          message match {
            case Message(_, SpeechAct.Request, Modality.Done, _) =>
              // remember: is doing it, have done it
              val actor = context.spawn(actuator, "actuator")
              actor ! message
            case _ =>
          }
          Behaviors.same
        }
      }
    }

  val system: ActorSystem[Message] =
    ActorSystem(observer, "hello")

  def perceive(event: Message) = system ! event
}
