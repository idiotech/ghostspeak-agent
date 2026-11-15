# User Variables Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a client read a player's current story variables with `GET /v1/scenario/{engine}/{scenarioId}/user/{userId}/variables`.

**Architecture:** A new `Sensor.Command.Inspect` travels the same path as `Sense`: the top-level sensor forwards it to the scenario's actor by scenario id, the scenario's per-user sensor forwards it to the player's actor by user id, and the player's `ScenarioCreator` actor replies from its state. The route asks the top-level sensor with `askWithStatus` like the other routes.

**Tech Stack:** Scala 3, Pekko typed + persistence, pekko-http, circe (`ConfiguredCodec` derivation), ScalaTest with `EventSourcedBehaviorTestKit`.

**Spec:** `docs/superpowers/specs/2026-11-15-staff-page-beacons-variables-design.md` (copied from ghostspeak-client)

## Global Constraints

- Response JSON is exactly `{"variables": {name: int}, "strings": {name: string}}`; both keys always present, empty maps allowed.
- 404 when the scenario or the player is unknown; 500 for any other failure.
- `sbt scalafmtAll` before every commit; `sbt test` green (GoldenJsonTest included).

## Review Focus

- A player who has joined but never triggered a variable update: `{"variables":{},"strings":{}}`, status 200. (Task 2 test.)
- An unknown player in a known scenario: 404, not a hang until the ask timeout. (Task 1 test: scenario-level sensor replies `Error("no such user")`; Task 3 maps it.)
- An unknown scenario: 404. (Task 1 test: top-level sensor replies `Error("no such scenario")`.)
- A blank user id segment is impossible in the route (`Segment` never matches empty), so `/user//variables` falls through to 404 rather than matching anything else.
- `getCommandName` must handle `Inspect`: today its `match` is not exhaustive, and an unhandled command would throw `MatchError` inside the player's actor. (Task 2 test exercises the command through `onCommand`, which calls `getCommandName` first.)

---

### Task 1: `Inspect` command and its routing in core `Sensor`

**Files:**
- Modify: `core/src/main/scala/tw/idv/idiotech/ghostspeak/agent/Sensor.scala` (`object Command`, `onCommand`, `onCommandPerUser`)
- Test: `core/src/test/scala/tw/idv/idiotech/ghostspeak/agent/SensorTest.scala`

**Interfaces:**
- Produces: `case class Inspect[P](scenarioId: String, userId: String, replyTo: ActorRef[StatusReply[String]]) extends Command[P]`. The reply is a JSON string. Top-level `onCommand`: forwards the same `Inspect` (same `replyTo`) to the scenario's child actor, `Effect.noReply`; replies `StatusReply.Error("no such scenario")` when there is none. `onCommandPerUser`: forwards to `getChild(ctx, userId)`; replies `StatusReply.Error("no such user")` when there is none (never creates a user actor).

- [ ] **Step 1: Write the failing tests** in `SensorTest`, using its existing `EventSourcedBehaviorTestKit` set-up for a sensor (top level) and for a per-user sensor (`sensor.apply(name, create, sensor.onCommandPerUser)`):

```scala
"inspecting an unknown scenario" should "fail with no such scenario" in {
  val reply = topLevel.runCommand[StatusReply[String]](Sensor.Command.Inspect("nope", "u1", _)).reply
  reply shouldBe StatusReply.Error("no such scenario")
}
"inspecting an unknown user" should "fail with no such user, creating no user actor" in {
  val reply = perUser.runCommand[StatusReply[String]](Sensor.Command.Inspect("scn", "ghost-user", _)).reply
  reply shouldBe StatusReply.Error("no such user")
  perUser.getState().scenarios shouldBe empty   // Sense would have persisted a Created; Inspect must not
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `sbt "core/testOnly *SensorTest"`
Expected: compile error, `Inspect` is not a member of `Sensor.Command`.

- [ ] **Step 3: Add `Inspect` to `Sensor.Command` and the two `case Inspect(...)` branches** in `onCommand` (after `Broadcast`) and `onCommandPerUser` (before `case _ => general`). Forwarding keeps the original `replyTo`, so the deep actor answers the asker directly.

- [ ] **Step 4: Run the tests**

Run: `sbt "core/testOnly *SensorTest"`
Expected: PASS, and every other case in the file still passes.

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add core/src/main/scala/tw/idv/idiotech/ghostspeak/agent/Sensor.scala core/src/test/scala/tw/idv/idiotech/ghostspeak/agent/SensorTest.scala
git commit -m "Add Sensor.Command.Inspect, routed to a player's actor like Sense"
```

### Task 2: The player's actor answers `Inspect` with its variables

**Files:**
- Modify: `example/src/main/scala/tw/idv/idiotech/ghostspeak/daqiaotou/ScenarioCreator.scala` (`onCommand` user branch, `getCommandName`)
- Modify: `example/src/main/scala/tw/idv/idiotech/ghostspeak/daqiaotou/package.scala` (new case class)
- Test: `example/src/test/scala/tw/idv/idiotech/ghostspeak/daqiaotou/ScenarioCreatorTest.scala`

**Interfaces:**
- Consumes: `Sensor.Command.Inspect` (Task 1).
- Produces: `case class UserVariables(variables: Map[String, Int], strings: Map[String, String]) derives ConfiguredCodec` in `package.scala`. In `ScenarioCreator.onCommand`: `case Sensor.Command.Inspect(_, _, replyTo) => Effect.reply(replyTo)(StatusReply.success(UserVariables(state.variables, state.strings).asJson.noSpaces))`. `getCommandName` returns `"inspect"` for it.

- [ ] **Step 1: Write the failing tests** in `ScenarioCreatorTest`, next to the existing user-scenario test. Reuse its `message`, `popup` and `EventSourcedBehaviorTestKit` set-up; add a script whose initial node, on the text reply `"yes"` (actionId `"ask"`), performs `Task.VariableUpdates(List(VariableUpdate("score", Operation.Plus, 3)))` wrapped like `popup` wraps a popup.

```scala
"answer an inspect with no variables before any update" in {
  val reply = user.runCommand[StatusReply[String]](Sensor.Command.Inspect("scn", "u1", _)).reply
  reply shouldBe StatusReply.success("""{"variables":{},"strings":{}}""")
}
"answer an inspect with the variables after an update" in {
  user.runCommand(Sensor.Command.Sense(message(Right(EventPayload.Text("yes")), Some("ask")).copy(sender = "u1", id = "m1")))
  val reply = user.runCommand[StatusReply[String]](Sensor.Command.Inspect("scn", "u1", _)).reply
  reply shouldBe StatusReply.success("""{"variables":{"score":3},"strings":{}}""")
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `sbt "example/testOnly *ScenarioCreatorTest"`
Expected: the first fails because the actor does not reply (`Effect.none` for unknown commands → the test kit reports no reply); not a compile error, since Task 1 added the command.

- [ ] **Step 3: Add `UserVariables` to `package.scala`, the `Inspect` branch to `onCommand` (before `case _ => Effect.none`), and the `getCommandName` case.** Key order in the JSON follows the case class, so the fixture strings above match `noSpaces`.

- [ ] **Step 4: Run the tests**

Run: `sbt "example/testOnly *ScenarioCreatorTest *GoldenJsonTest"`
Expected: PASS. If GoldenJsonTest enumerates client-facing types and now demands a fixture for `UserVariables`, add it per the README (`GOLDEN_UPDATE=1`), and commit the fixture.

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add example/src/main/scala/tw/idv/idiotech/ghostspeak/daqiaotou/ScenarioCreator.scala example/src/main/scala/tw/idv/idiotech/ghostspeak/daqiaotou/package.scala example/src/test/scala/tw/idv/idiotech/ghostspeak/daqiaotou/ScenarioCreatorTest.scala
git commit -m "Answer Inspect with the player's variables and strings"
```

### Task 3: The HTTP route

**Files:**
- Modify: `example/src/main/scala/tw/idv/idiotech/ghostspeak/daqiaotou/EventRoutes.scala` (`routes` list)

**Interfaces:**
- Consumes: `Sensor.Command.Inspect`; the inherited `given timeout: Timeout = 3.seconds` and `sensor.askWithStatus[String]`.
- Produces: `GET /v1/scenario/{engine}/{scenarioId}/user/{userId}/variables` → 200 with the JSON string from Task 2 as `application/json`; `Failure(StatusReply.ErrorMessage("no such scenario" | "no such user"))` → 404 with the reason; any other `Failure` → 500 with the message.

- [ ] **Step 1: Add the route** as a new element of `routes`, after the `actions` route, mirroring the `Broadcast` route's `onComplete(sensor.askWithStatus[String](x => Inspect(scenarioId, userId, x)))` shape. Complete the success case with `HttpEntity(ContentTypes.`application/json`, msg)` so the body is not re-encoded as a JSON string.

- [ ] **Step 2: Compile and run the suite**

Run: `sbt scalafmtCheckAll test`
Expected: all green, including `HttpStartupTest`.

- [ ] **Step 3: Manual check against a staged server** (there is no http-testkit dependency; do not add one for this):

```bash
sbt example/stage   # then start it with the usual env, and with a joined player u1 in scenario scn:
curl -i http://localhost:8080/v1/scenario/graphscript/scn/user/u1/variables     # 200, {"variables":{...},"strings":{...}}
curl -i http://localhost:8080/v1/scenario/graphscript/scn/user/nobody/variables # 404 no such user
curl -i http://localhost:8080/v1/scenario/graphscript/nope/user/u1/variables    # 404 no such scenario
```

- [ ] **Step 4: Commit**

```bash
git add example/src/main/scala/tw/idv/idiotech/ghostspeak/daqiaotou/EventRoutes.scala
git commit -m "Serve a player's variables at GET .../user/{userId}/variables"
```
