package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.jdbc.db.SlickExtension
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.{ Create, Query }

import java.sql.{ Connection, DriverManager }
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

/** Runs the real JDBC journal and snapshot store against MySQL. Opt-in: set
  * GHOSTSPEAK_IT_MYSQL_HOST (and optionally _PORT, _USER, _PASSWORD); the test creates and drops
  * its own database. Without it every test is canceled, not failed.
  */
class MySqlPersistenceTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val env = sys.env.get("GHOSTSPEAK_IT_MYSQL_HOST").map { host =>
    (
      host,
      sys.env.getOrElse("GHOSTSPEAK_IT_MYSQL_PORT", "3306"),
      sys.env.getOrElse("GHOSTSPEAK_IT_MYSQL_USER", "root"),
      sys.env.getOrElse("GHOSTSPEAK_IT_MYSQL_PASSWORD", "")
    )
  }
  private val database = s"ghostspeak_it_${UUID.randomUUID().toString.take(8)}"
  private val poolSize = 3

  private def serverConnection(): Connection = {
    val (host, port, user, password) = env.get
    // DriverManager discovers drivers once per JVM; register ours in case another classloader
    // (an earlier cross-version test run in the same sbt) got there first
    Class.forName("com.mysql.cj.jdbc.Driver")
    DriverManager.getConnection(s"jdbc:mysql://$host:$port/?useSSL=false", user, password)
  }

  private def sql(statement: String): Unit = {
    val c = serverConnection()
    try c.createStatement().execute(statement)
    finally c.close()
  }

  private def queryString(query: String): String = {
    val c = serverConnection()
    try {
      val rs = c.createStatement().executeQuery(query)
      rs.next()
      rs.getString(1)
    } finally c.close()
  }

  private def config: Config = {
    val (host, port, user, password) = env.get
    ConfigFactory
      .parseString(s"""
        MYSQL_HOST = "$host"
        MYSQL_PORT = "$port"
        MYSQL_JOURNAL_DB = "$database"
        MYSQL_USER = "$user"
        MYSQL_PASSWORD = "$password"
        MYSQL_POOL_SIZE = $poolSize
        pekko.actor.serializers.testcirce = "tw.idv.idiotech.ghostspeak.agent.TestCirceSerializer"
        pekko.actor.serialization-bindings {
          "tw.idv.idiotech.ghostspeak.agent.EventBase" = testcirce
        }
        """)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
  }

  private var testKit: ActorTestKit = _
  private implicit def system: ActorSystem[_] = testKit.system

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (env.isDefined) {
      sql(s"CREATE DATABASE $database")
      testKit = ActorTestKit("MySqlPersistenceTest", config)
      Await.result(SchemaUtils.createIfNotExists(), 30.seconds)
    }
  }

  override def afterAll(): Unit = {
    if (env.isDefined) {
      if (testKit != null) testKit.shutdownTestKit()
      sql(s"DROP DATABASE IF EXISTS $database")
    }
    super.afterAll()
  }

  private def requireMySql(): Unit =
    assume(env.isDefined, "set GHOSTSPEAK_IT_MYSQL_HOST to run the MySQL tests")

  private val sensor = new Sensor[String]

  private def spawnSensor(name: String): ActorRef[Sensor.Command[String]] =
    testKit.spawn(
      sensor(
        name,
        (ctx, created) =>
          Right(
            ctx.spawn(
              Behaviors.ignore[Sensor.Command[String]],
              if (created.uniqueId.nonEmpty) created.uniqueId else UUID.randomUUID().toString
            )
          )
      )
    )

  private def create(ref: ActorRef[Sensor.Command[String]], scenario: Scenario): Unit = {
    val probe = testKit.createTestProbe[StatusReply[String]]()
    ref ! Create(scenario, probe.ref)
    probe.expectMessageType[StatusReply[String]](10.seconds).isSuccess shouldBe true
  }

  // the scenario ids the sensor holds, read through a command so it waits for recovery
  private def scenarioIds(ref: ActorRef[Sensor.Command[String]]): Set[String] = {
    val probe = testKit.createTestProbe[StatusReply[String]]()
    ref ! Query(None, None, None, None, None, probe.ref)
    val json = probe.expectMessageType[StatusReply[String]](30.seconds).getValue
    io.circe.parser.decode[List[Scenario]](json).toOption.get.map(_.id).toSet
  }

  private def stop(ref: ActorRef[_]): Unit =
    testKit.stop(ref, 10.seconds)

  "the persistence plugins" should {
    "share one connection pool" in {
      requireMySql()
      val ext = SlickExtension(system)
      val journalDb = ext.database(system.settings.config.getConfig("jdbc-journal")).database
      val snapshotDb =
        ext.database(system.settings.config.getConfig("jdbc-snapshot-store")).database
      val readJournalDb =
        ext.database(system.settings.config.getConfig("jdbc-read-journal")).database
      (journalDb eq snapshotDb) shouldBe true
      (journalDb eq readJournalDb) shouldBe true
    }

    "open no more connections than the pool size, even with many recoveries" in {
      requireMySql()
      val names = (1 to 30).map(i => s"pool-$i")
      val refs = names.map(spawnSensor)
      refs.zipWithIndex.foreach { case (ref, i) => create(ref, Scenario(s"s$i", "e", "[]")) }
      refs.foreach(stop)
      // recover all 30 at once
      val again = names.map(spawnSensor)
      again.foreach(r => scenarioIds(r).size shouldBe 1)
      again.foreach(stop)
      val open = queryString(
        s"SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB = '$database'"
      ).toInt
      open should (be > 0 and be <= poolSize)
    }
  }

  "the snapshot table" should {
    "be widened to LONGBLOB, so snapshots over 64 KB are stored and recovered" in {
      requireMySql()
      val columnType =
        s"""SELECT DATA_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = '$database' AND TABLE_NAME = 'snapshot'
            AND COLUMN_NAME = 'snapshot_payload'"""
      queryString(columnType) shouldBe "blob" // as created by pekko-persistence-jdbc
      Await.result(SnapshotSchemaMigration(), 30.seconds)
      queryString(columnType) shouldBe "longblob"
      Await.result(SnapshotSchemaMigration(), 30.seconds) // second run is a no-op

      val big = "x" * 1000
      val ref = spawnSensor("big")
      (1 to 100).foreach(i => create(ref, Scenario(s"s$i", "e", big)))
      stop(ref)
      val size = queryString(
        s"SELECT LENGTH(snapshot_payload) FROM $database.snapshot WHERE persistence_id = 'sensor-big'"
      ).toInt
      size should be > 100000

      // delete the events the snapshot covers, so the state can only come from the snapshot
      sql(s"DELETE FROM $database.event_journal WHERE persistence_id = 'sensor-big'")
      scenarioIds(spawnSensor("big")) shouldBe (1 to 100).map(i => s"s$i").toSet
    }
  }

  "an unreadable snapshot" should {
    "be skipped, and the state rebuilt from the whole journal" in {
      requireMySql()
      val ref = spawnSensor("corrupt")
      (1 to 5).foreach(i => create(ref, Scenario(s"s$i", "e", "[]")))
      stop(ref)
      // a snapshot that fails to decode, like a pre-fix user-scenario snapshot
      sql(s"""INSERT INTO $database.snapshot
          (persistence_id, sequence_number, created, snapshot_ser_id, snapshot_ser_manifest,
           snapshot_payload)
          VALUES ('sensor-corrupt', 5, 0, 5567, '${classOf[Sensor.State].getName}',
                  '{"not":"a sensor state"}')""")
      scenarioIds(spawnSensor("corrupt")) shouldBe (1 to 5).map(i => s"s$i").toSet
    }
  }
}
