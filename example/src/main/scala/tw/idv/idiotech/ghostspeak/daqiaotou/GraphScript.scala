package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.generic.extras.ConfiguredJsonCodec
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  FcmSender,
  Scenario,
  Sensor,
  Session,
  SystemPayload,
  Action => BaseAction,
  Message => BaseMessage
}
import tw.idv.idiotech.ghostspeak.agent.util.substitute
import tw.idv.idiotech.ghostspeak.daqiaotou.Volume.StaticVolume

object GraphScript {

//  implicit val contentEncoder = BaseAction.encoder[Content]
//  implicit val contentDecoder = BaseAction.decoder[Content]
//  implicit val messageEncoder = BaseMessage.encoder[EventPayload]
//  implicit val messageDecoder = BaseMessage.decoder[EventPayload]
  @ConfiguredJsonCodec
  case class Node(name: String, trigger: Message, actions: List[Action], children: List[Node]) {

    def replace(user: String): Node =
      Node(
        name,
        substitute(trigger, "?u", user),
        actions.map(substitute(_, "?u", user)),
        children.map(_.replace(user))
      )
    def childMap() = children.map(n => n.trigger -> n).toMap
  }

  def action1(user: String) = BaseAction[Content](
    "action 1",
    user,
    "ghost",
    Content(
      Task.Popup(Some("你有聽見我說話嗎？"), List("請說", "(離開吧，對你比較好)"), false, Nil, Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )

  def action1_1(user: String) = BaseAction[Content](
    "action 1",
    user,
    "ghost",
    Content(
      Task.Popup(Some("沒有人聽見我說話，到底什麼時候信才能送到她手上呢？"), Nil, false, Nil, Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )

  def action2(user: String) = BaseAction[Content](
    "action 2",
    user,
    "ghost",
    Content(
      Task.Popup(
        Some("幫我把這封信交給她吧，時間不多，返航的船笛已經在響了。"),
        Nil,
        false,
        List("http://daqiaotou-storage.floraland.tw/images/letter.png"),
        Set(Destination.App)
      ),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )

  def action3(user: String) = BaseAction[Content](
    "action 3",
    user,
    "ghost",
    Content(
      Task.Popup(Some("之後一定會再跟你聯絡的。"), Nil, false, Nil, Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )

  def action4(user: String) = BaseAction[Content](
    "action 4",
    user,
    "ghost",
    Content(
      Task.Marker(Location(24.0, 120.0), "", ""),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )

  def action5(user: String) = BaseAction[Content](
    "action 5",
    user,
    "ghost",
    Content(
      Task.MarkerRemoval("action 4"),
      Condition.Geofence(Location(24.0, 120.0), 100)
    ),
    Session("scenario1", Some(""))
  )

  def action6(user: String) = BaseAction[Content](
    "action 6",
    user,
    "ghost",
    Content(
      Task.Sound(
        "http://daqiaotou-storage.floraland.tw/sounds/reply.mp3",
        StaticVolume(None),
        SoundType.Main
      ),
      Condition.Geofence(Location(24.0, 120.0), 100)
    ),
    Session("scenario1", Some(""))
  )

  //
  def message1(user: String) =
    agent.Message[EventPayload](
      "",
      actionId = None,
      receiver = "ghost",
      sender = user,
      payload = Left(SystemPayload.Join),
      scenarioId = "scenario1"
    )

  def message2(user: String) =
    agent.Message[EventPayload](
      "",
      actionId = Some("action 1"),
      receiver = "ghost",
      sender = user,
      payload = Right(EventPayload.Text("請說")),
      scenarioId = "scenario1"
    )

  def message2_1(user: String) =
    agent.Message[EventPayload](
      "",
      actionId = Some("action 1"),
      receiver = "ghost",
      sender = user,
      payload = Right(EventPayload.Text("(離開吧，對你比較好)")),
      scenarioId = "scenario1"
    )

  def message3(user: String) =
    agent.Message[EventPayload](
      "",
      actionId = Some("action 2"),
      receiver = "ghost",
      sender = user,
      payload = Left(SystemPayload.End),
      scenarioId = "scenario1"
    )

  def initialState(user: String) = Map(
    message1(user)   -> List(action1(user)),
    message2_1(user) -> List(action1_1(user)),
    message2(user)   -> List(action2(user), action4(user), action5(user), action6(user)),
    message3(user)   -> List(action3(user))
  )

  def exampleNode(user: String) = Node(
    "node1",
    message1(user).forComparison,
    List(action1(user)),
    List(
      Node("node2", message2_1(user).forComparison, List(action1_1(user)), Nil),
      Node(
        "node3",
        message2(user).forComparison,
        List(action2(user), action4(user), action5(user), action6(user)),
        List(
          Node("node4", message3(user).forComparison, List(action3(user)), Nil)
        )
      )
    )
  )

}
