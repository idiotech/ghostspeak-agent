package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.generic.extras.ConfiguredJsonCodec
import tw.idv.idiotech.ghostspeak.agent.util.substitute

object GraphScript {

  @ConfiguredJsonCodec
  case class Performance(action: Action, delay: Long)

  @ConfiguredJsonCodec
  case class Node(
    name: String,
    triggers: List[Message],
    performances: List[Performance],
    exclusiveWith: Set[String],
    children: List[String]
  ) {

    def replace(user: String): Node =
      Node(
        name,
        triggers.map(trigger => substitute(trigger, "?u", user)),
        performances.map(p => Performance(substitute(p.action, "?u", user), p.delay)),
        exclusiveWith,
        children
      )

    def childMap(user: String)(implicit map: Map[String, Node]): Map[Message, Node] =
      children
        .flatMap(name => map.get(name).map(_.replace(user)).map(n => n.triggers.map(_ -> n)))
        .flatten
        .toMap
  }
  object Node {
    val leave = Node("leave", Nil, Nil, Set.empty, Nil)
  }
}
