package tw.idv.idiotech.ghostspeak.daqiaotou

import cats.implicits.*
import io.circe.{ Json, ParsingFailure }
import io.circe.parser.parse
import io.circe.syntax.*

import java.util.concurrent.atomic.AtomicLong

/** Keeps the actions waiting in Redis in the order they were performed.
  *
  * The actions wait in a hash, which has no order, so a step's chat lines used to reach the client
  * shuffled. Each stored action now carries an increasing number under [[OrderKey]]; the fetch
  * sorts by it and takes it out again, so the client sees the same action JSON as before.
  */
object PendingActions {

  val OrderKey = "_order"

  // microseconds since the epoch, bumped by one when two actions are stored in the same one, so
  // the numbers keep growing within this server and roughly follow the clock across restarts
  private val last = new AtomicLong(0L)

  def nextOrder(now: Long = System.currentTimeMillis()): Long =
    last.updateAndGet(previous => math.max(previous + 1, now * 1000))

  /** The JSON to store for an action, numbered with `order`. */
  def stamp(action: Json, order: Long): Json =
    action.mapObject(_.add(OrderKey, order.asJson))

  /** The stored actions in the order they were performed, without their numbers. Actions stored
    * before the numbering have none and come first.
    */
  def inOrder(stored: Iterable[String]): Either[ParsingFailure, List[Json]] =
    stored.toList.traverse(parse).map { actions =>
      actions
        .sortBy(_.hcursor.get[Long](OrderKey).getOrElse(Long.MinValue))
        .map(_.mapObject(_.remove(OrderKey)))
    }
}
