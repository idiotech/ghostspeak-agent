package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import tw.idv.idiotech.ghostspeak.agent.Session

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, Executors }
import scala.concurrent.{ Await, ExecutionContext, ExecutionContextExecutorService, Future }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class PendingActionsTest extends AnyFlatSpec with Matchers {

  private def action(id: String, text: String = ""): Json =
    Json.obj("id" -> id.asJson, "receiver" -> "u".asJson, "text" -> text.asJson)

  private def ids(actions: List[Json]) = actions.map(_.hcursor.get[String]("id").toOption.get)

  "PendingActions" must "hand out the stored actions in the order they were stored" in {
    val stored = (1 to 20).map(i => action(s"line-$i")).toList
    PendingActions.readable(stored.map(_.noSpaces)) mustBe stored
  }

  it must "leave out what is not JSON and keep the rest" in {
    val stored = List(action("a").noSpaces, "{not json", action("b").noSpaces)
    ids(PendingActions.readable(stored)) mustBe List("a", "b")
  }

  it must "hand out an action stored twice once: where it first was, as it last was" in {
    val stored = List(action("q1", "first"), action("l2"), action("q1", "again")).map(_.noSpaces)
    PendingActions.readable(stored) mustBe List(action("q1", "again"), action("l2"))
  }

  it must "keep every action that has no id" in {
    val noId = Json.obj("receiver" -> "u".asJson)
    PendingActions.readable(List(noId.noSpaces, noId.noSpaces)) mustBe List(noId, noId)
  }

  it must "keep the list and the old hash under different keys" in {
    PendingActions.listKey("s", "u") must not be PendingActions.legacyHashKey("s", "u")
    // the hash key as servers before the list wrote it
    PendingActions.legacyHashKey("s", "u") mustBe "action-s-u"
    PendingActions.keys("s", "u") mustBe List("actions-s-u", "action-s-u")
  }

  it must "hand out a whole action the clients decode unchanged" in {
    val full: Action = tw.idv.idiotech.ghostspeak.agent.Action(
      "p1",
      "u",
      "ghost",
      Content(Task.Popup(Some("hi"), self = true), Condition.Always),
      Session("s", None)
    )
    PendingActions.readable(List(full.asJson.toString)).map(_.as[Action]) mustBe List(Right(full))
  }

  private def pool(threads: Int) =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(threads))

  "KeyedQueue" must "run the tasks of a key in the order given, on any number of threads" in {
    given ec: ExecutionContextExecutorService = pool(16)
    try {
      val queue = new KeyedQueue
      val ran = new ConcurrentLinkedQueue[Int]()
      // the earlier a task, the longer it takes: run at once, they would finish in reverse
      val tasks = (1 to 30).map(i => queue.run("k") { Thread.sleep(30 - i); ran.add(i); i })
      Await.result(Future.sequence(tasks), 10.seconds) mustBe (1 to 30)
      ran.asScala.toList mustBe (1 to 30)
    } finally ec.shutdown()
  }

  it must "not make one key wait for another" in {
    given ec: ExecutionContextExecutorService = pool(4)
    try {
      val queue = new KeyedQueue
      val release = new CountDownLatch(1)
      val blocked = queue.run("slow")(release.await())
      Await.result(queue.run("other")("done"), 5.seconds) mustBe "done"
      blocked.isCompleted mustBe false
      release.countDown()
      Await.result(blocked, 5.seconds)
    } finally ec.shutdown()
  }

  it must "run the tasks after one that failed" in {
    given ec: ExecutionContextExecutorService = pool(4)
    try {
      val queue = new KeyedQueue
      val failed = queue.run[Int]("k")(throw new RuntimeException("redis is away"))
      val next = queue.run("k")(2)
      Await.ready(failed, 5.seconds).value.get.isFailure mustBe true
      Await.result(next, 5.seconds) mustBe 2
    } finally ec.shutdown()
  }

  "DistinctStartTimes" must "move a time already given out to the next free millisecond" in {
    val times = new DistinctStartTimes
    List(1000L, 1000L, 1000L, 1001L, 5000L, 900L).map(times.next) mustBe
    List(1000L, 1001L, 1002L, 1003L, 5000L, 900L)
  }

  "Popup" must "keep the player's own line flag" in {
    val json = parse("""{"text":"me","self":true,"type":"POPUP"}""").toOption.get
    json.as[Task].map { case p: Task.Popup => p.self; case _ => false } mustBe Right(true)
    (Task.Popup(Some("me"), self = true): Task).asJson.hcursor.get[Boolean]("self") mustBe Right(
      true
    )
  }

  it must "read a popup without the flag as a character's line" in {
    val json = parse("""{"text":"npc","type":"POPUP"}""").toOption.get
    json.as[Task].map { case p: Task.Popup => p.self; case _ => true } mustBe Right(false)
  }
}
