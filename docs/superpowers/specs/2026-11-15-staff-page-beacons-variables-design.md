# Staff page: detected beacons and story variables

Date: 2026-11-15. Status: approved.

## Intent

On the staff page (`DebugActivity`, opened by long-pressing the logo), show every beacon the phone currently
sees and the player's current story variables, so staff can tell where a player's run stands.

## Goals, non-goals

- Goals: all sighted beacons with signal strength; the player's integer and string variables with their values;
  values fresh after each trigger.
- Non-goals: editing variables from the phone; variables anywhere but the staff page; history of values.
- Anti-goals: polling the server while the staff page is closed; any change to how the story engine applies
  variables.

## Constraints

- The phone has never received variable values: the editor compiles variable updates into the story, the agent
  applies them to each player's state inside the scenario actor, and nothing exposes them. The feature needs an
  agent change.
- Old agents do not have the route; the staff page must say so instead of failing.

## Approaches

1. **A read endpoint on the agent** (picked): accurate, small on both sides, needs an agent deploy.
2. The server pushes values to the phone as a new action after every update: works without requests but adds
   a protocol type on both sides and storage on the phone.
3. Mirroring updates on the phone: impossible, the server never sends the updates.

## Design

### Agent (`ghostspeak-agent`, own PR)

- New sensor command `Inspect`, routed to the player's scenario actor the way `Sense` is, answered from the
  actor's state with its integer variables and its string variables (text replies saved as variables).
- New route `GET /v1/scenario/{engine}/{scenarioId}/user/{userId}/variables` returning
  `{"variables": {name: int}, "strings": {name: string}}`; the empty object for a player the scenario has not seen yet (a 404 would read as a missing route on the phone, and a player whose Join is still on its way is routine); 404 only for an unknown scenario.
- Tests with the existing actor test harness: join, apply an update, inspect.

### Phone (`ghostspeak-client`, own PR)

- `GhostSpeakService.getVariables(scenarioId, userId)` and `EventController.getVariables` with the short retry;
  the blank-path guard covers blank ids.
- Staff page: a "變數" card listing name and value, sorted by name; refreshed when the page opens (per
  controller) and after each new entry in the trigger history, debounced to one request per second. Empty state
  says there are none yet. A 404 shows that the server does not support it yet.
- Beacons: the three fixed slots (minor 1, 2, 3) become one list of every beacon in the latest scan, as
  "minor: dBm", sorted by minor, with the scan time. Beacons no longer seen drop out on the next scan.

### Calls made by the builder, open to correction

- Strings are shown alongside integers.
- Refresh is driven by history entries, not by polling.
- The agent change ships as its own PR; the phone feature degrades gracefully until it is deployed.
