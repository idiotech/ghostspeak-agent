package tw.idv.idiotech.ghostspeak.agent.knowledge

trait State

trait Action

sealed trait ActionStatus
object ActionStatus {
  case object Doing extends ActionStatus
  case object Done extends ActionStatus
  case object Failed extends ActionStatus
}

case class Event[T <: Action](action: T, status: ActionStatus, time: Long)

case class Speak(actor: String, receiver: String, content: String) extends Action

// perception -> intention = action,
// register perceptions
// trigger: intention = action, register another perception based on graph, remove from graph
// case class Trigger(input: Action, output: List[Action], transition: List[Trigger]
