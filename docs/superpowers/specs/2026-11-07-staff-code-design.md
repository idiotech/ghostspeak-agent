# Staff code for the story listing

Status: approved by the owner on 2026-11-07 (option B, passcode leak fixed, other leaks deferred).

## Why

The Android staff page lists unpublished stories by calling `GET v1/scenario` without `public=true`.
Nothing checks who asks, so anyone with `curl` gets every unpublished story. The iOS Settings screen
does the same by sending `public=false`. Every listing also carries each story's `metadata.passcode`,
which defeats the passcode feature. Field staff still need the staff page on release builds at
events, so the fix is a server-checked staff code, not a debug-only build.

## Goals

- Unpublished stories are listed only to callers holding a valid staff token.
- Staff type a shared staff code about once per event day; the app keeps a short-lived token, not
  the code.
- Story passcodes are never sent to non-staff callers.
- Old app versions keep working: they get published stories only, not errors.

## Non-goals (deferred by the owner)

- Protecting `PUT`/`DELETE v1/scenario/{engine}/{id}`, `POST v1/broadcast` and `PUT v1/category`.
- Hiding the full script served by `GET v1/scenario/{engine}/{id}` (the k8s probes call it).
- Per-person staff accounts, or reusing ghostspeak-auth (it only answers "is this Firebase uid a
  superuser" and takes no credentials).
- iOS changes. The server change alone closes the iOS Settings leak; the iOS staff page already
  sends `public=true`.

## Anti-goals

- The staff code committed to any repository, printed in logs, or echoed in responses.
- The iOS passcode box (`GET v1/scenario?passcode=…`) breaking.
- The health probes (`GET v1/scenario/graphscript/test`) needing a token.

## Approaches

- **A. Code on every request** (`X-Staff-Code`). Smallest; the app holds the code in memory and
  staff retype it after every restart.
- **B. Exchange the code for a 12-hour signed token (chosen).** One extra endpoint and an HMAC
  check; the app stores only the token, and rotating the code revokes every token.
- Reusing ghostspeak-auth was ruled out: it has no secret to check.

## Design

### Secret and rotation

- The server reads `STAFF_CODE` from the environment (`app.staff-code` in
  `core/src/main/resources/application.conf`). Unset or empty means staff access is off: every
  token request is refused and listings behave as for non-staff.
- In k8s it comes from the existing Secret `agent-secret`, key `staff_code`, through a
  `secretKeyRef` with `optional: true` in `urbanbaker/k8s/urbanbaker/deployment-agent.yaml`. The
  owner adds the key with kubectl; it never enters git.
- Rotation: change the key in `agent-secret`, then `kubectl rollout restart deployment/agent -n
  urbanbaker`. Every existing token stops working at once, because the signing key is derived from
  the code.
- The code should be at least 16 random characters. There is no rate limiting (my call: a long
  random code makes guessing impractical, and a limiter in a single replica adds state for little).

### Token

- `POST v1/staff/session` with body `{"code": "<staff code>"}`.
  - Right code: `200 {"token": "<token>", "expiresAt": <epoch millis>}`.
  - Wrong code, empty code, or staff access off: `401`, same body for all three.
  - Malformed body: `400`, as for other routes.
- Token: `<expiry epoch millis>.<base64url, no padding, of HMAC-SHA256(key, "staff:" + expiry)>`,
  where `key = SHA-256("ghostspeak-staff:" + code)`. Valid for 12 hours. Signature comparison with
  `MessageDigest.isEqual`. No server state.
- Clients send it as `Authorization: Bearer <token>`.

### `GET v1/scenario` (the listing)

| Request | Before | After |
| --- | --- | --- |
| `public=true` (any other params) | published | unchanged |
| `passcode=X`, no token | stories with passcode X | unchanged |
| no `public` / `public=false`, no token | all / unpublished | published only (`public` forced to `true`) |
| any params, valid token | as requested | unchanged (as requested) |
| any params, invalid or expired token | as requested | `401` |

- `featured` and `category` filters still apply on top.
- `metadata.passcode` is removed from every story in the response unless the token is valid.

### `GET v1/scenario/{engine}/{id}`

Unchanged except that `metadata.passcode` is removed unless the token is valid (my call: same leak,
same fix; the full script stays exposed as deferred).

### Android client (blocked: repository not reachable from this session)

- Replace the "if you are staff" dialog with a code prompt; keep the 2-second hold to hide the page.
- On submit, call `POST v1/staff/session`; on `200` store the token and its expiry (app-private
  storage) and open the staff page; on `401` say the code is wrong.
- Send `Authorization: Bearer <token>` on `getScenarios(null)`. On `401`, drop the token and ask
  for the code again. If the stored token has expired, ask before opening the page.

### Rollout

1. Owner adds `staff_code` to `agent-secret`.
2. Deploy the server and the deployment yaml change. From here, listings without a token show
   published stories only, on old Android staff pages too, and the iOS Settings picker shows
   published stories instead of unpublished ones.
3. Release the Android client. Staff on older builds see published stories only until they update.

### Tests (server)

- Token: right code issues a token that validates; wrong, empty and unset codes are refused;
  an expired token, a token signed with an old code, and garbage tokens are refused.
- Listing: no token gives published only (with no param and with `public=false`); valid token
  gives unpublished; invalid token gives `401`; `public=true` unchanged; `passcode` lookup
  unchanged; passcode stripped without a token and kept with one; probe route works with no token.

## Left to the builder

Names of classes and test files, how the route reads the header, how the test sensor is stubbed.
