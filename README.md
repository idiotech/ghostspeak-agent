# ghostspeak-agent

The server side of Ghostspeak scenarios, written in Scala 3 with Pekko. Clients send events over
HTTP; scenario scripts decide which actions to send back, by FCM push and through Redis. State is
event-sourced in MySQL (pekko-persistence-jdbc).

- `core`: the generic agent (sensors, actuators, persistence, HTTP routes)
- `example`: the graphscript and spotkeeper engines and the server (`ServerInstance`)

## Build and test

```
sbt test             # unit tests
sbt scalafmtCheckAll # formatting (sbt scalafmtAll to fix)
sbt example/stage    # the server, under example/target/universal/stage
```

The integration tests run only when these are set; otherwise they are canceled:

```
GHOSTSPEAK_IT_MYSQL_HOST=... [GHOSTSPEAK_IT_MYSQL_PORT GHOSTSPEAK_IT_MYSQL_USER GHOSTSPEAK_IT_MYSQL_PASSWORD]
GHOSTSPEAK_IT_REDIS_HOST=... [GHOSTSPEAK_IT_REDIS_PORT]
```

`GoldenJsonTest` pins the JSON of everything persisted or sent to clients. After a deliberate
format change, regenerate its fixtures with `GOLDEN_UPDATE=1 sbt "example/testOnly *GoldenJsonTest"`.

## Configuration

Set through environment variables (see the `application.conf` files): `ENGINE`, `MYSQL_*`,
`MYSQL_POOL_SIZE`, `MAX_CONCURRENT_RECOVERIES`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_TIMEOUT_MS`,
`FCM_KEY_FILE`, `CORS_DOMAINS` (required: the server does not start without it).

At startup the server checks that the journal and snapshot payload columns are `LONGBLOB`. A
`BLOB` column holds at most 64 KB, so the server logs the `ALTER TABLE` that widens it; run it
by hand, or start one instance once with `WIDEN_PAYLOAD_COLUMNS=true`.
