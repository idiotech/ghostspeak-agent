package tw.idv.idiotech.ghostspeak.daqiaotou

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.UpperSnakecase
import io.circe.generic.extras.ConfiguredJsonCodec
import json.schema.typeHint
import tw.idv.idiotech.ghostspeak.agent.util.substitute
import tw.idv.idiotech.ghostspeak.daqiaotou.BeaconType.findValues

import scala.collection.immutable

object GraphScript {

  @ConfiguredJsonCodec
  case class Performance(action: Action, delay: Long)

  @typeHint[String]
  sealed trait Comparison extends EnumEntry

  object Comparison extends Enum[Comparison] with CirceEnum[Comparison] {
    val values: immutable.IndexedSeq[Comparison] = findValues

    case object >= extends Comparison with UpperSnakecase

    case object <= extends Comparison with UpperSnakecase

    case object == extends Comparison with UpperSnakecase

  }

  @ConfiguredJsonCodec
  case class Precondition(name: String, comparison: Comparison, value: Int)

  @ConfiguredJsonCodec
  case class Node(
    name: String,
    triggers: List[Message],
    performances: List[Performance],
    preconditions: List[Precondition] = Nil,
    exclusiveWith: Set[String],
    children: List[String]
  ) {

    def replace(user: String): Node =
      Node(
        name,
        triggers.map(trigger => substitute(trigger, "?u", user)),
        performances.map(p => Performance(substitute(p.action, "?u", user), p.delay)),
        preconditions,
        exclusiveWith,
        children
      )

    def childMap(user: String)(implicit map: Map[String, Node]) =
      children
        .flatMap(name => map.get(name).map(_.replace(user)).map(n => n.triggers.map(_ -> n)))
        .flatten
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap

  }

  object Node {
    val leave = Node("leave", Nil, Nil, Nil, Set.empty, Nil)
  }
}
