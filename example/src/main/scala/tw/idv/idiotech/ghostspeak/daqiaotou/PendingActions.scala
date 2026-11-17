package tw.idv.idiotech.ghostspeak.daqiaotou

import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import io.circe.parser.parse
import org.apache.pekko.actor.typed.{ ActorSystem, DispatcherSelector }

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Try

/** The actions performed for a user and not yet fetched by the client, kept in Redis in the order
  * they were performed: a step's chat lines are shown as they come, and the reply box only opens
  * when the question is the last one.
  *
  * They wait in a list, one per user and scenario. Servers before this one kept them in a hash,
  * which has no order; what is still in a hash is handed out first and never added to.
  */
object PendingActions extends LazyLogging {

  def listKey(scenario: String, user: String) = s"actions-$scenario-$user"

  // must stay a different name from the list's: the two are different Redis types
  def legacyHashKey(scenario: String, user: String) = s"action-$scenario-$user"

  /** Every key holding actions for the user, for deleting them. */
  def keys(scenario: String, user: String): List[String] =
    List(listKey(scenario, user), legacyHashKey(scenario, user))

  private val writes = new KeyedQueue

  /** Adds the action after the ones already stored for the user. Calls for one user reach Redis one
    * at a time, in the order they were made, so the list has the order of the calls even though
    * each write runs on its own thread.
    */
  def store(scenario: String, user: String, actionJson: String)(using
    system: ActorSystem[?]
  ): Future[Unit] = {
    val key = listKey(scenario, user)
    writes.run(key)(redis.rpush(key, actionJson))(using
      system.dispatchers.lookup(DispatcherSelector.blocking())
    )
  }

  /** Takes the stored actions out of Redis: first what an older server left in the hash, in any
    * order, then the list in the order it was written.
    */
  def take(store: RedisStore, scenario: String, user: String): List[String] = {
    val hashKey = legacyHashKey(scenario, user)
    val legacy = store.hgetAll(hashKey)
    store.hdel(hashKey, legacy.keys.toSeq*)
    legacy.values.toList ++ store.takeList(listKey(scenario, user))
  }

  /** The actions to hand to the client, in the order given. One that is not JSON is left out: it is
    * already out of Redis, and must not cost the client the others. An action stored twice under
    * one id is handed out once, as the hash did: where it first was, as it last was.
    */
  def readable(stored: List[String]): List[Json] = {
    val parsed = stored.flatMap { s =>
      parse(s).fold(
        error => {
          logger.error(s"leaving out a stored action that is not JSON: $s", error)
          None
        },
        Some(_)
      )
    }
    def id(action: Json) = action.hcursor.get[String]("id").toOption
    val latest = parsed.flatMap(a => id(a).map(_ -> a)).toMap
    val seen = scala.collection.mutable.Set.empty[String]
    parsed.flatMap { action =>
      id(action) match {
        case None                   => Some(action)
        case Some(i) if seen.add(i) => latest.get(i)
        case Some(_)                => None
      }
    }
  }
}

/** Runs the tasks given for one key one at a time, in the order they were given; tasks for
  * different keys do not wait for each other. A task that fails does not stop the ones after it.
  */
final private[daqiaotou] class KeyedQueue {

  // the last task given for each key that has one waiting or running
  private val last = new ConcurrentHashMap[String, Future[?]]()

  def run[A](key: String)(task: => A)(using ec: ExecutionContext): Future[A] = {
    val done = Promise[A]()
    Option(last.put(key, done.future)) match {
      case Some(previous) => previous.onComplete(_ => done.complete(Try(task)))
      case None           => ec.execute(() => done.complete(Try(task)))
    }
    done.future.onComplete(_ => last.remove(key, done.future))(using ExecutionContext.parasitic)
    done.future
  }
}
