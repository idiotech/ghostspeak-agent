package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.parser.parse
import io.circe.{ Decoder, Encoder, Json, Printer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  CategoryManager,
  Metadata,
  Modality,
  Scenario,
  Sensor,
  Session,
  SystemPayload,
  Action => BaseAction,
  Message => BaseMessage
}
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.{ ActTime, Comparison, Node, Precondition }
import tw.idv.idiotech.ghostspeak.daqiaotou.SpotKeeper.{ Spot, SpotPayload }

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path, Paths }
import java.time.LocalTime

/** Pins the JSON of everything that is persisted (journal events, snapshots) or exchanged with
  * clients. The fixtures under src/test/resources/golden were generated from the codecs as they
  * were before the move away from circe macro annotations, so any change in the codecs (field
  * names, discriminators, defaults, field order) fails here.
  *
  * Each fixture must decode to its value, and encoding the value must give the fixture byte for
  * byte. Run with GOLDEN_UPDATE=1 to (re)write the fixtures after a deliberate format change.
  *
  * The "legacy" fixtures are hand-written, decode-only inputs with optional fields left out, as
  * older clients and older journal entries have them.
  */
class GoldenJsonTest extends AnyFlatSpec with Matchers {

  private val printer = Printer.spaces2
  private val update = sys.env.get("GOLDEN_UPDATE").contains("1")

  private val goldenDir: Path =
    Seq("example/src/test/resources/golden", "src/test/resources/golden")
      .map(Paths.get(_))
      .find(p => Files.isDirectory(p.getParent))
      .get

  private def golden[A: Encoder: Decoder](name: String, value: A): Unit =
    name must "keep its JSON" in {
      val file = goldenDir.resolve(s"$name.json")
      val encoded = printer.print(Encoder[A].apply(value)) + "\n"
      if (update) {
        Files.createDirectories(goldenDir)
        Files.write(file, encoded.getBytes(UTF_8))
      }
      val fixture = new String(Files.readAllBytes(file), UTF_8)
      parse(fixture).flatMap(_.as[A]) mustBe Right(value)
      encoded mustBe fixture
    }

  private def legacy[A: Decoder](name: String, json: String, expected: A): Unit =
    name must "decode its legacy JSON" in {
      parse(json).flatMap(_.as[A]) mustBe Right(expected)
    }

  private def rejected[A: Decoder](name: String, json: String): Unit =
    name must "keep rejecting its JSON" in {
      parse(json).flatMap(_.as[A]).isLeft mustBe true
    }

  // ---- shared sample values

  val session = Session("romeo and juliet", Some("chapter 1"))
  val location = Location(24.1, 120.6)
  val mainVolume = MainVolume(10, 3, 1)

  val tasks: List[(String, Task)] = List(
    "popup" -> Task.Popup(
      Some("Do you love me?"),
      List("yes", "no"),
      true,
      List("https://example.com/a.jpg"),
      Set(Destination.App, Destination.Notification),
      false,
      true
    ),
    "popup-dismissal" -> Task.PopupDismissal(Set(Destination.Alert)),
    "incoming-call"   -> Task.IncomingCall("Juliet", IncomingCallType.Connecting, "p.png"),
    "sound-static" -> Task.Sound(
      "https://example.com/a.mp3",
      Volume.StaticVolume(Some(12), 5, 2, Some(mainVolume)),
      SoundType.Main
    ),
    "sound-dynamic" -> Task.Sound(
      "https://example.com/b.mp3",
      Volume.DynamicVolume(location, 30, 0.2, None),
      SoundType.Background
    ),
    "sound-beacon" -> Task.Sound(
      "https://example.com/c.mp3",
      Volume.BeaconVolume("beacon-1", -90, 0.1, -40, Some(mainVolume)),
      SoundType.Loop
    ),
    "marker"         -> Task.Marker(location, "icon.png", "Balcony", Some("a-popup")),
    "marker-removal" -> Task.MarkerRemoval("a-marker"),
    "map-style"      -> Task.MapStyle(Some("https://example.com/style"), true),
    "intro-image" -> Task
      .IntroImage(Some("bg.png"), Some("logo.png"), Some(1.5f), None, Some(20f), None),
    "button-style" -> Task.ButtonStyle("#000000", "#ffffff"),
    "popup-style"  -> Task.PopupStyle("#ff0000"),
    "variable-updates" -> Task.VariableUpdates(
      List(
        VariableUpdate("score", Operation.+, 2),
        VariableUpdate("lives", Operation.-, 1),
        VariableUpdate("level", Operation.`=`, 3)
      )
    ),
    "variable-update"     -> VariableUpdate("score", Operation.+, 1),
    "end-game"            -> Task.EndGame,
    "guide-image"         -> Task.GuideImage("guide.png"),
    "guide-image-removal" -> Task.GuideImageRemoval,
    "silence"             -> Task.Silence("a-sound", 4),
    "friend"              -> Task.Friend("f1", "Mercutio", Some("m.png"))
  )

  val conditions: List[(String, Condition)] = List(
    "always"   -> Condition.Always,
    "geofence" -> Condition.Geofence(location, 14),
    "beacon"   -> Condition.Beacon("beacon-1", -70, BeaconType.Enter)
  )

  def action(id: String, task: Task = tasks.head._2, condition: Condition = Condition.Always) =
    BaseAction[Content](id, "Romeo", "Juliet", Content(task, condition, List("other")), session)

  val actionWithDescription = action("a1").copy(description = Some("ask the question"))

  def message(payload: agent.Payload[EventPayload], actionId: Option[String] = Some("a1")) =
    BaseMessage[EventPayload]("m1", actionId, "ghost", "Romeo", payload, "scn-1")

  val payloads: List[(String, agent.Payload[EventPayload])] = List(
    "ack"              -> Left(SystemPayload.Ack),
    "start"            -> Left(SystemPayload.Start),
    "end"              -> Left(SystemPayload.End),
    "join"             -> Left(SystemPayload.Join),
    "leave"            -> Left(SystemPayload.Leave),
    "modal"            -> Left(SystemPayload.Modal(Modality.Done, 1700000000000L)),
    "text"             -> Right(EventPayload.Text("yes", Some("answer"))),
    "location"         -> Right(location),
    "golden-finger"    -> Right(EventPayload.GoldenFinger),
    "perform-directly" -> Right(EventPayload.PerformDirectly(action("direct")))
  )

  val metadata = Metadata(
    Some("rj"),
    Some("Romeo and Juliet"),
    Some("a tragedy"),
    true,
    true,
    Some("1234"),
    Some("owner@example.com"),
    42L,
    List("drama", "classic"),
    Some("details"),
    Some("cover.png")
  )

  val scenario =
    Scenario("scn-1", "graphscript", "[]", Some("rj"), Some("R&J"), true, Some("o"), 7L, metadata)

  val node = Node(
    "n1",
    List(message(Left(SystemPayload.Start)), message(Right(EventPayload.Text("yes")), None)),
    List(
      GraphScript
        .Performance(action("p1"), Some(ActTime(Some(LocalTime.of(21, 10, 5)), 1000L, 60000L)), 0L),
      GraphScript.Performance(action("p2", tasks(3)._2, conditions(1)._2), None, 5000L)
    ),
    List(Precondition("score", Comparison.>=, 3), Precondition("lives", Comparison.==, 1)),
    Set("n2"),
    List("n2", "n3")
  )

  val spot = Spot("SPOT-1", "Balcony", "Look up", List("a.png"), location)

  // Event and State are path-dependent on these instances
  val scenarioCreator: ScenarioCreator =
    new ScenarioCreator(new Sensor[EventPayload], new Actuator[Content, EventPayload])
  val actuator: Actuator[Content, EventPayload] = new Actuator[Content, EventPayload]

  val category =
    CategoryManager.Category("c1", "Drama", 1, false, CategoryManager.DisplayType.Carousel, 5)

  // ---- client-facing JSON

  tasks.foreach { case (name, t) => golden[Task](s"task-$name", t) }
  conditions.foreach { case (name, c) => golden[Condition](s"condition-$name", c) }
  golden[BaseAction[Content]]("action", actionWithDescription)
  payloads.foreach { case (name, p) => golden[Message](s"message-$name", message(p)) }
  golden[Message]("message-no-action-id", message(Left(SystemPayload.Join), None))
  golden[SystemPayload]("system-payload-modal", SystemPayload.Modal(Modality.Intended, 1L))
  golden[Scenario]("scenario", scenario)
  golden[Metadata]("metadata", metadata)
  golden[agent.EventRoutes.ScenarioPayload](
    "scenario-payload",
    agent.EventRoutes.ScenarioPayload(Json.arr(Json.fromString("node")), metadata)
  )
  golden[EventRoutes.Resources]("resources", EventRoutes.Resources(List("a.png"), List("a.mp3")))
  golden[PerfLog]("perf-log", PerfLog("u1", "a1", "fcm_sending"))
  golden[Sensor.Identifier]("identifier", Sensor.Identifier("graphscript", "scn-1"))
  golden[SpotPayload]("spot-payload-upload", SpotPayload.Upload(spot))
  golden[SpotPayload]("spot-payload-show", SpotPayload.Show(spot))
  golden[SpotPayload]("spot-payload-dump", SpotPayload.Dump("u1"))

  // ---- persisted: journal events and snapshots

  golden[Node]("node", node)
  golden[scenarioCreator.Event](
    "scenario-event",
    scenarioCreator.Event(
      List(node),
      message(Right(EventPayload.Text("yes"))),
      List(VariableUpdate("score", Operation.+, 1)),
      List(scenarioCreator.StringVariable("answer", "yes"))
    )
  )
  golden[scenarioCreator.State](
    "scenario-state",
    scenarioCreator.State(
      Map(message(Left(SystemPayload.Start)) -> List(node)),
      Map("a1"                               -> List("n2")),
      Map("score"                            -> 3),
      Map("answer"                           -> "yes")
    )
  )
  golden[Sensor.Event]("sensor-event-created", Sensor.Event.Created(scenario, "uid-1"))
  golden[Sensor.Event]("sensor-event-destroyed", Sensor.Event.Destroyed("scn-1"))
  golden[Sensor.State]("sensor-state", Sensor.State().add(Sensor.Event.Created(scenario, "uid-1")))
  golden[actuator.Event](
    "actuator-event-performance",
    actuator.Event.Performance(action("a1"), 1700000000000L)
  )
  golden[actuator.Event](
    "actuator-event-action-done",
    actuator.Event.ActionDone(Set(actuator.Event.Performance(action("a1"), 1700000000000L)))
  )
  golden[actuator.State](
    "actuator-state",
    actuator.State(Set(actuator.Event.Performance(action("a1", tasks(6)._2, conditions(2)._2), 5L)))
  )
  golden[SpotKeeper.Event]("spotkeeper-event", SpotKeeper.Event.Uploaded(spot))
  golden[SpotKeeper.State]("spotkeeper-state", SpotKeeper.State(List(spot)))
  golden[CategoryManager.Event](
    "category-event",
    CategoryManager.Event.Uploaded(CategoryManager.State(List(category)))
  )
  golden[CategoryManager.State]("category-state", CategoryManager.State(List(category)))

  // ---- legacy inputs: optional fields left out

  legacy[Metadata]("metadata-empty", "{}", Metadata())
  legacy[Scenario](
    "scenario-minimal",
    """{"id":"s","engine":"graphscript","template":"[]"}""",
    Scenario("s", "graphscript", "[]")
  )
  legacy[Task]("task-popup-minimal", """{"type":"POPUP"}""", Task.Popup())
  legacy[Task](
    "task-static-volume-minimal",
    """{"type":"SOUND","url":"u","volumeSetting":{"type":"STATIC_VOLUME"},"mode":"MAIN"}""",
    Task.Sound("u", Volume.StaticVolume(mainVolume = None), SoundType.Main)
  )
  legacy[Content](
    "content-minimal",
    """{"task":{"type":"END_GAME"},"condition":{"type":"ALWAYS"}}""",
    Content(Task.EndGame, Condition.Always)
  )
  legacy[BaseAction[Content]](
    "action-minimal",
    """{"id":"a","receiver":"r","sender":"s",
       "content":{"task":{"type":"END_GAME"},"condition":{"type":"ALWAYS"}},
       "session":{"scenario":"scn"}}""",
    BaseAction[Content](
      "a",
      "r",
      "s",
      Content(Task.EndGame, Condition.Always),
      Session("scn", None)
    )
  )
  legacy[GraphScript.Performance](
    "graphscript-performance-minimal",
    """{"action":{"id":"a","receiver":"r","sender":"s",
       "content":{"task":{"type":"END_GAME"},"condition":{"type":"ALWAYS"}},
       "session":{"scenario":"scn"}}}""",
    GraphScript.Performance(
      BaseAction[Content](
        "a",
        "r",
        "s",
        Content(Task.EndGame, Condition.Always),
        Session("scn", None)
      )
    )
  )
  legacy[Node](
    "node-minimal",
    """{"name":"n","triggers":[],"performances":[],"exclusiveWith":[],"children":[]}""",
    Node("n", Nil, Nil, Nil, Set.empty, Nil)
  )
  legacy[Message](
    "message-text-minimal",
    """{"id":"m","receiver":"ghost","sender":"u","payload":{"type":"TEXT","text":"hi"},"scenarioId":"s"}""",
    BaseMessage[EventPayload]("m", None, "ghost", "u", Right(EventPayload.Text("hi")), "s")
  )
  legacy[MainVolume](
    "main-volume-minimal",
    """{"speechLength":10,"fadeOutSeconds":3}""",
    MainVolume(10, 3)
  )
  legacy[Task](
    "task-popup-dismissal-minimal",
    """{"type":"POPUP_DISMISSAL"}""",
    Task.PopupDismissal()
  )
  legacy[Task](
    "task-marker-minimal",
    """{"type":"MARKER","location":{"lat":1.0,"lon":2.0},"icon":"i","title":"t"}""",
    Task.Marker(Location(1.0, 2.0), "i", "t")
  )
  legacy[Task]("task-map-style-minimal", """{"type":"MAP_STYLE"}""", Task.MapStyle(None))
  legacy[Task](
    "task-intro-image-minimal",
    """{"type":"INTRO_IMAGE"}""",
    Task.IntroImage(None, None, mapLogoUrl = None)
  )
  legacy[EventPayload](
    "event-payload-text-minimal",
    """{"type":"TEXT","text":"hi"}""",
    EventPayload.Text("hi")
  )
  // journal events written before string variables existed have no "strings" field
  legacy[scenarioCreator.Event](
    "scenario-event-before-strings",
    """{"nodes":[],"message":{"id":"m","receiver":"ghost","sender":"u",
       "payload":{"type":"START"},"scenarioId":"s"},"variableUpdates":[]}""",
    scenarioCreator.Event(
      Nil,
      BaseMessage[EventPayload]("m", None, "ghost", "u", Left(SystemPayload.Start), "s"),
      Nil
    )
  )
  // the actuator's events use plain (not configured) derivation, which ignores default values:
  // a missing field is an error, not the default. Every stored ActionDone has "actions".
  rejected[actuator.Event]("actuator-event-action-done-empty", """{"type":"ACTION_DONE"}""")
  legacy[CategoryManager.Category](
    "category-minimal",
    """{"id":"c","name":"n","order":1,"hidden":false}""",
    CategoryManager.Category("c", "n", 1, false)
  )
}
