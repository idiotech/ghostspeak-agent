# Staff Code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Only callers holding a staff token see unpublished stories or story passcodes.

**Architecture:** A pure `StaffAuth` class issues and checks HMAC-signed 12-hour tokens derived from
`STAFF_CODE`. `EventRoutes` gains a `POST v1/staff/session` route, reads `Authorization: Bearer`
on the scenario GET routes, forces `public=true` for non-staff listings and strips
`metadata.passcode` for non-staff.

**Tech Stack:** Scala 3.3, Pekko HTTP 1.0.1, circe, ScalaTest, pekko-http-testkit.

**Spec:** `docs/superpowers/specs/2026-11-07-staff-code-design.md`

## Global Constraints

- Token format: `<expiry epoch millis>.<base64url no padding of HMAC-SHA256(SHA-256("ghostspeak-staff:" + code), "staff:" + expiry)>`; lifetime 12 hours.
- Unset or empty `STAFF_CODE`: no token is ever issued or accepted.
- Wrong, empty or disabled code: `401`, identical body.
- The staff code is never logged or returned.
- `GET v1/scenario/graphscript/test` keeps working with no token.

## Review Focus

- A token with extra dots, a non-numeric expiry, or bad base64 must be `401`, not `500`.
- `public=false` without a token must return published stories, not unpublished ones.
- `passcode=X` with no token must keep returning the matching unpublished story.
- `Authorization` with a non-Bearer scheme (e.g. Basic) is treated as an invalid token: `401`.
- The passcode is stripped from the single-story route too, not only the listing.

---

### Task 1: `StaffAuth`

**Files:**
- Create: `core/src/main/scala/tw/idv/idiotech/ghostspeak/agent/StaffAuth.scala`
- Modify: `core/src/main/resources/application.conf` (add `staff-code = ""` and `staff-code = ${?STAFF_CODE}` under `app`)
- Test: `core/src/test/scala/tw/idv/idiotech/ghostspeak/agent/StaffAuthTest.scala`

**Interfaces:**
- Produces: `class StaffAuth(code: Option[String], now: () => Long = () => System.currentTimeMillis())`
  with `def issue(candidate: String): Option[StaffAuth.Token]` and `def isValid(token: String): Boolean`;
  `case class StaffAuth.Token(token: String, expiresAt: Long) derives Codec.AsObject`;
  `StaffAuth.Lifetime: Long = 12 * 3600 * 1000`; `StaffAuth.fromConfig(): StaffAuth` reads `app.staff-code` (empty → `None`).

- [ ] Step 1: tests: `issue("right")` returns a token that `isValid`; `issue("wrong")`, `issue("")` are `None`; `StaffAuth(None).issue("")` and `StaffAuth(Some("")).issue("")` are `None`; a token is invalid once `now` passes `expiresAt`; a token from `StaffAuth(Some("old"))` is invalid for `StaffAuth(Some("new"))`; `"garbage"`, `"1.2.3"`, `"abc.def"`, `""` are invalid without throwing; a token with its expiry edited upward is invalid.
- [ ] Step 2: run `sbt "core/testOnly *StaffAuthTest"`; expect compile failure.
- [ ] Step 3: implement with `javax.crypto.Mac`, `MessageDigest.isEqual`, `Base64.getUrlEncoder.withoutPadding`.
- [ ] Step 4: run the test; expect PASS.
- [ ] Step 5: commit.

### Task 2: routes

**Files:**
- Modify: `core/src/main/scala/tw/idv/idiotech/ghostspeak/agent/EventRoutes.scala`
- Modify: `build.sbt` (add `"org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test`)
- Test: `core/src/test/scala/tw/idv/idiotech/ghostspeak/agent/StaffRoutesTest.scala`

**Interfaces:**
- Consumes: `StaffAuth` from Task 1.
- Produces: `EventRoutes` gets a 4th constructor parameter `staffAuth: StaffAuth = StaffAuth.fromConfig()` (subclass and `ServerInstance` untouched).

- [ ] Step 1: tests against `concat(routes*)` with a stub sensor that answers `Query` by applying the same filters to a fixed list (one published story with passcode "p1", one unpublished with passcode "p2"):
  - `POST v1/staff/session {"code":"right"}` → 200 with token; `{"code":"wrong"}` → 401.
  - `GET v1/scenario` no token → only the published id, and no `passcode` key in its metadata.
  - `GET v1/scenario?public=false` no token → only the published id.
  - `GET v1/scenario` valid token → both ids, passcodes present.
  - `GET v1/scenario` with `Bearer garbage`, an expired token, or `Basic xyz` → 401.
  - `GET v1/scenario?public=true` → only the published id.
  - `GET v1/scenario?passcode=p2` no token → the unpublished id, passcode stripped.
  - `GET v1/scenario/graphscript/<unpublished id>` no token → 200, no passcode; with token → passcode present.
- [ ] Step 2: run `sbt "core/testOnly *StaffRoutesTest"`; expect failures.
- [ ] Step 3: implement: a directive `staffAccess: Directive1[Boolean]` (no header → false; valid Bearer → true; anything else → `complete(401)`); listing uses `isPublic = if staff || passcode.isDefined then requested else Some(true)`; the JSON selector removes `metadata.passcode` unless staff; add the `POST v1/staff/session` route.
- [ ] Step 4: run the whole suite `sbt test` and `sbt scalafmtCheckAll`; expect PASS. `GoldenJsonTest` must be unchanged (it pins stored and pushed JSON, not route responses).
- [ ] Step 5: commit.

### Task 3: config and docs

**Files:**
- Modify: `README.md` (configuration table: `STAFF_CODE`)
- Modify (repo `idiotech/urbanbaker`): `k8s/urbanbaker/deployment-agent.yaml` add `STAFF_CODE` from `secretKeyRef {name: agent-secret, key: staff_code, optional: true}`

- [ ] Step 1: edit both; in urbanbaker run a YAML parse check.
- [ ] Step 2: commit each on branch `staff-code`; push both.

### Task 4 (blocked): Android client

Waits for access to `idiotech/ghostspeak-client`; follow the spec's Android section.
