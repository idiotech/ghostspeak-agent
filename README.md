# ghostspeak-agent

The server side of Ghostspeak scenarios, written in Scala 3 with Pekko. Clients send events over
HTTP; scenario scripts decide which actions to send back, by FCM push and through Redis. State is
event-sourced in MySQL (pekko-persistence-jdbc).

- `core`: the generic agent (sensors, actuators, persistence, HTTP routes)
- `example`: the graphscript and spotkeeper engines and the server (`ServerInstance`)

## Build and test

```
sbt test             # all tests; the integration suites are canceled unless configured below
sbt scalafmtCheckAll # formatting (sbt scalafmtAll to fix)
sbt example/stage    # the server, under example/target/universal/stage
```

The integration suites run only when these are set; otherwise they are canceled, not failed:

| Variable | Default |
| --- | --- |
| `GHOSTSPEAK_IT_MYSQL_HOST` | none: the MySQL suites are canceled |
| `GHOSTSPEAK_IT_MYSQL_PORT` | `3306` |
| `GHOSTSPEAK_IT_MYSQL_USER` | `root` |
| `GHOSTSPEAK_IT_MYSQL_PASSWORD` | empty |
| `GHOSTSPEAK_IT_REDIS_HOST` | none: the Redis suite is canceled |
| `GHOSTSPEAK_IT_REDIS_PORT` | `6379` |

The MySQL suite creates and drops its own database and alters columns in it, so its user needs
those privileges. Point it at a scratch server, never at production.

`GoldenJsonTest` pins the JSON of everything persisted or sent to clients. After a deliberate
format change, regenerate its fixtures with `GOLDEN_UPDATE=1 sbt "example/testOnly *GoldenJsonTest"`.

`git-hooks/pre-commit` checks formatting with the
[scalafmt CLI](https://scalameta.org/scalafmt/docs/installation.html#cli), which must be on the
`PATH`; enable it with `git config core.hooksPath git-hooks`.

## Configuration

Environment variables, read by `core/src/main/resources/application.conf` and
`example/src/main/resources/application.conf`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ENGINE` | `graphscript` | `graphscript` (HTTP on port 8080) or `spotkeeper` (port 8081). Any other value starts nothing. |
| `CORS_DOMAINS` | none, **required** | Allowed origins: comma-separated, each with its scheme, no spaces, e.g. `https://a.example.com,https://b.example.com`. The server does not start without it. |
| `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_JOURNAL_DB` | none, **required** | The MySQL server and database holding the event journal and snapshots. |
| `MYSQL_USER`, `MYSQL_PASSWORD` | none, **required** for the journal | Credentials for the journal and for the performance log. |
| `MYSQL_DATABASE` | `ghostspeak-log` | The database of the performance log (`PerformanceLogger`), on the same server. |
| `MYSQL_POOL_SIZE` | `20` | Journal connections per instance. |
| `MAX_CONCURRENT_RECOVERIES` | half of `MYSQL_POOL_SIZE` (at least 1) | How many scenario actors replay their events at once. |
| `WIDEN_PAYLOAD_COLUMNS` | `false` | See below. |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Where actions wait for clients to fetch them. |
| `REDIS_TIMEOUT_MS` | `10000` | Redis connect and read timeout; `0` waits indefinitely. |
| `FCM_KEY_FILE` | a developer's path, so **required** in practice | The Firebase service-account JSON for push messages. A missing file is not detected at startup: the first push fails. |
| `PENDING_ICON`, `ARRIVED_ICON` | empty | Map-marker icon URLs for the spotkeeper engine. |

At build time, `IMAGE_TAG` (default `localtest`) tags the Docker image built by
`sbt example/Docker/publishLocal`.

### Payload columns

At startup the server checks that the journal and snapshot payload columns are `LONGBLOB`. A
`BLOB` column holds at most 64 KB, so the server logs the `ALTER TABLE` that widens it; run it
by hand, or start one instance once with `WIDEN_PAYLOAD_COLUMNS=true`.

The `ALTER TABLE` copies each table and locks it for writes meanwhile, which takes a while on a
large journal. It runs before the HTTP port is bound and has no timeout, so the instance answers
nothing until it is done. Run it in a maintenance window, and disable or extend the readiness and
liveness probes for that start, or the orchestrator may kill the instance mid-copy.
