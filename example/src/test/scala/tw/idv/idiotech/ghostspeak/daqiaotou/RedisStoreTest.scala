package tw.idv.idiotech.ghostspeak.daqiaotou

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** Runs against a real Redis server. Opt-in: set GHOSTSPEAK_IT_REDIS_HOST (and optionally
  * GHOSTSPEAK_IT_REDIS_PORT); without it every test is canceled, not failed.
  */
class RedisStoreTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val host = sys.env.get("GHOSTSPEAK_IT_REDIS_HOST")
  private val port = sys.env.get("GHOSTSPEAK_IT_REDIS_PORT").map(_.toInt).getOrElse(6379)
  private lazy val store = new RedisStore(host.get, port)
  private def key(name: String) = s"ghostspeak-it-${UUID.randomUUID()}-$name"

  override def afterAll(): Unit = {
    if (host.isDefined) store.close()
    super.afterAll()
  }

  private def requireRedis(): Unit =
    assume(host.isDefined, "set GHOSTSPEAK_IT_REDIS_HOST to run the Redis tests")

  // Speaks the Redis protocol directly, independent of any client library, to check what is
  // actually stored and to write data the way another client (or an older server) did.
  private def raw(args: String*): String = {
    val socket = new Socket(host.get, port)
    try {
      val out: OutputStream = socket.getOutputStream
      val parts = args.map(_.getBytes(UTF_8))
      out.write(s"*${parts.size}\r\n".getBytes(UTF_8))
      parts.foreach { p =>
        out.write(s"$$${p.length}\r\n".getBytes(UTF_8))
        out.write(p)
        out.write("\r\n".getBytes(UTF_8))
      }
      out.flush()
      val in = new java.io.DataInputStream(new java.io.BufferedInputStream(socket.getInputStream))
      def readLine(): String = {
        val bytes = new java.io.ByteArrayOutputStream()
        var b = in.read()
        while (b != '\r') { bytes.write(b); b = in.read() }
        in.read() // '\n'
        new String(bytes.toByteArray, UTF_8)
      }
      val line = readLine()
      if (line.startsWith("$")) {
        // bulk string: the length is in bytes
        val len = line.drop(1).toInt
        if (len < 0) null
        else {
          val bytes = new Array[Byte](len)
          in.readFully(bytes)
          new String(bytes, UTF_8)
        }
      } else line
    } finally socket.close()
  }

  private val json = """{"id":"a1","content":{"task":{"choices":["關閉"],"type":"POPUP"}}}"""

  "RedisStore" must "store and read back a hash, including non-ASCII text" in {
    requireRedis()
    val k = key("roundtrip")
    store.hset(k, "a1", json)
    store.hset(k, "a2", "second")
    store.hgetAll(k) mustBe Map("a1" -> json, "a2" -> "second")
    store.del(k)
  }

  it must "store values as UTF-8, readable by any other client" in {
    requireRedis()
    val k = key("utf8")
    store.hset(k, "a1", "關閉")
    raw("HGET", k, "a1") mustBe "關閉"
    raw("HSTRLEN", k, "a1") mustBe ":6" // two CJK characters, three bytes each
    store.del(k)
  }

  it must "read values written by another client" in {
    requireRedis()
    val k = key("foreign")
    raw("HSET", k, "a1", json)
    store.hgetAll(k) mustBe Map("a1" -> json)
    store.del(k)
  }

  it must "return an empty map for a missing key" in {
    requireRedis()
    store.hgetAll(key("missing")) mustBe empty
  }

  it must "serve many concurrent operations" in {
    // many clients fetching their actions at once; the pool must not cap them at 8
    requireRedis()
    import scala.concurrent.{ Await, Future }
    import scala.concurrent.duration._
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.fromExecutor(
        java.util.concurrent.Executors.newFixedThreadPool(50)
      )
    val k = key("concurrent")
    val writes = Future.traverse((1 to 50).toList)(i => Future(store.hset(k, s"a$i", s"v$i")))
    Await.result(writes, 10.seconds)
    store.hgetAll(k).size mustBe 50
    Await.result(Future.traverse((1 to 50).toList)(i => Future(store.hdel(k, s"a$i"))), 10.seconds)
    store.hgetAll(k) mustBe empty
  }

  it must "delete several fields in one call, and do nothing for none" in {
    requireRedis()
    val k = key("batch")
    (1 to 5).foreach(i => store.hset(k, s"a$i", s"v$i"))
    store.hdel(k, "a1", "a2", "a3")
    store.hgetAll(k) mustBe Map("a4" -> "v4", "a5" -> "v5")
    store.hdel(k)
    store.hgetAll(k).size mustBe 2
    store.del(k)
  }

  /** A server that accepts connections and never replies, for `test(port)`. */
  private def withSilentServer(test: Int => Unit): Unit = {
    val server = new java.net.ServerSocket(0)
    // keep the accepted sockets referenced: a collected socket is closed, which resets the
    // client's connection
    val accepted = new java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    val acceptor = new Thread(() =>
      while (!server.isClosed) scala.util.Try(server.accept()).foreach(accepted.add)
    )
    acceptor.setDaemon(true)
    acceptor.start()
    try test(server.getLocalPort)
    finally {
      server.close()
      accepted.forEach(_.close())
    }
  }

  it must "time out a call when a timeout is set and Redis does not answer" in {
    requireRedis()
    withSilentServer { port =>
      val timed = new RedisStore("127.0.0.1", port, timeoutMs = 300)
      try {
        val start = System.nanoTime()
        an[Exception] must be thrownBy timed.hgetAll("anything")
        (System.nanoTime() - start) / 1000000 must be < 5000L
      } finally timed.close()
    }
  }

  it must "by default keep waiting past Jedis's own 2 s timeout, as redisclient did" in {
    requireRedis()
    withSilentServer { port =>
      val untimed = new RedisStore("127.0.0.1", port)
      @volatile var outcome: Option[scala.util.Try[Map[String, String]]] = None
      val call = new Thread(() => outcome = Some(scala.util.Try(untimed.hgetAll("anything"))))
      call.setDaemon(true)
      call.start()
      call.join(3000)
      // still waiting after 3 s; closing the server afterwards ends the call
      try withClue(s"the call ended with $outcome: ")(call.isAlive mustBe true)
      finally untimed.close()
    }
  }

  it must "delete one field, then the whole key" in {
    requireRedis()
    val k = key("delete")
    store.hset(k, "a1", "x")
    store.hset(k, "a2", "y")
    store.hdel(k, "a1")
    store.hgetAll(k) mustBe Map("a2" -> "y")
    store.hdel(k, "missing") // no error
    store.del(k)
    store.hgetAll(k) mustBe empty
    raw("EXISTS", k) mustBe ":0"
  }
}
