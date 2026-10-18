package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.Codec
import tw.idv.idiotech.ghostspeak.agent.jsoncodec.semiauto.deriveConfiguredCodec

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.UpperSnakecase
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

  object ActTime {
    implicit val codecForActTime: Codec.AsObject[ActTime] = deriveConfiguredCodec[ActTime]
  }

  case class Performance(action: Action, time: Option[ActTime] = None, delay: Long = 0)

  object Performance {

    implicit val codecForPerformance: Codec.AsObject[Performance] =
      deriveConfiguredCodec[Performance]
  }

  sealed trait Comparison extends EnumEntry

  object Comparison extends Enum[Comparison] with CirceEnum[Comparison] {
    val values: immutable.IndexedSeq[Comparison] = findValues

    case object >= extends Comparison with UpperSnakecase

    case object <= extends Comparison with UpperSnakecase

    case object == extends Comparison with UpperSnakecase

  }

  case class Precondition(name: String, comparison: Comparison, value: Int)

  object Precondition {

    implicit val codecForPrecondition: Codec.AsObject[Precondition] =
      deriveConfiguredCodec[Precondition]
  }

  // state
  // 1. 劇本： node map HashMap<Id, Node>
  // 2. HashMap<Event, Node>
  // 3. Matching event -> Node

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
        .map { case (k, v) =>
          (k.forComparison, v.map(_._2))
        }
  }

  object Node {
    implicit val codecForNode: Codec.AsObject[Node] = deriveConfiguredCodec[Node]

    val leave = Node("leave", Nil, Nil, Nil, Set.empty, Nil)
  }
}
