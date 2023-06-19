package tw.idv.idiotech.ghostspeak.daqiaotou

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.UpperSnakecase
import io.circe.generic.extras.ConfiguredJsonCodec
import json.schema.typeHint
import tw.idv.idiotech.ghostspeak.agent.util.substitute
import tw.idv.idiotech.ghostspeak.daqiaotou.BeaconType.findValues

import java.time.{
  Clock,
  Instant,
  LocalDate,
  LocalDateTime,
  LocalTime,
  ZoneId,
  ZoneOffset,
  ZonedDateTime
}
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.util.Random

object GraphScript {

  val defaultZone = ZoneId.of("Asia/Taipei")

  @ConfiguredJsonCodec
  case class ActTime(target: Option[LocalTime], minAfter: Long, range: Long) {

    def time(clock: Clock = Clock.system(defaultZone)): Long = {
      println(s"calculating time for $this")
      val baseline = clock.millis() + minAfter
      val baselineLocalDataTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(baseline), defaultZone)
      val exactTime = target.fold {
        println(s"get baseline for $this: $baseline")
        baseline
      } { expectedLocalTime =>
        ZonedDateTime
          .of(
            LocalDateTime
              .of(baselineLocalDataTime.toLocalDate, expectedLocalTime)
              .plusDays(if (expectedLocalTime.isAfter(baselineLocalDataTime.toLocalTime)) 0 else 1),
            defaultZone
          )
          .toInstant
          .toEpochMilli
      }
      val ret =
        exactTime + (if (range > 0) Random.nextLong(range) else 0) * (if (Random.nextBoolean()) 1
                                                                      else -1)
      println(s"calculating time for $this: $ret")
      ret
    }
  }

  @ConfiguredJsonCodec
  case class Performance(action: Action, time: Option[ActTime] = None, delay: Long = 0)

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
        performances.map(p => Performance(substitute(p.action, "?u", user), p.time, p.delay)),
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
