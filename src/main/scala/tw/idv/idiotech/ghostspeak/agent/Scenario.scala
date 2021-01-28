package tw.idv.idiotech.ghostspeak.agent

object Scenario {
  sealed trait Command
  case object Destroy extends Command
  case class Sense(message: Message) extends Command

}
