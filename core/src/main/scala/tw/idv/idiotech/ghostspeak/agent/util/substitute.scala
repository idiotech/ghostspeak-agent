package tw.idv.idiotech.ghostspeak.agent.util

import tw.idv.idiotech.ghostspeak.agent.{ Action, Message }

/** Replaces the top-level fields of a Message or an Action whose value is exactly `original`
  * (scripts use "?u" for the user ID). Only fields whose value is a String count; strings inside
  * an Option, a payload, a session or a content are left alone.
  */
object substitute {

  def apply[T](m: Message[T], original: String, updated: String): Message[T] = {
    def s(v: String) = if (v == original) updated else v
    m.copy(
      id = s(m.id),
      receiver = s(m.receiver),
      sender = s(m.sender),
      scenarioId = s(m.scenarioId)
    )
  }

  def apply[C](a: Action[C], original: String, updated: String): Action[C] = {
    def s(v: String) = if (v == original) updated else v
    val content = a.content match {
      case str: String => s(str).asInstanceOf[C]
      case other       => other
    }
    a.copy(id = s(a.id), receiver = s(a.receiver), sender = s(a.sender), content = content)
  }
}
