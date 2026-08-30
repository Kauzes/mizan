# Mizan

A merchant payments platform built as a set of Spring Boot services around a double entry
ledger. Payments are authorized, captured, refunded and settled through an event driven
flow, and every movement of money lands as a balanced journal entry.

*Mizan* is the Turkish accounting term for a trial balance, the report that proves the
books balance. That is this system's core invariant: the sum of every posting in the
platform is always zero.

## Status

Early construction. Features land one Jira issue at a time and this README grows with
them. Nothing below is claimed until it is in the repo and covered by a test.

| Milestone | Scope | State |
|---|---|---|
| M1 | Foundation, identity, ledger core, payment happy path | in progress |
| M2 | Kafka outbox, risk scoring, refunds and saga compensation | not started |
| M3 | Merchant webhooks, React merchant console | not started |
| M4 | Settlement, reconciliation, observability | not started |
| M5 | Kubernetes delivery, load and chaos testing | not started |
| M6 | Android merchant app, documentation | not started |

## Services

| Module | Port | Responsibility |
|---|---|---|
| `gateway` | 8080 | Routing, JWT validation, rate limiting, correlation id propagation |
| `identity-service` | 8081 | Merchants, users, roles, JWT tokens, merchant API keys |
| `ledger-service` | 8082 | Double entry accounts, journal entries, postings, reconciliation |
| `payment-service` | 8083 | Payment lifecycle and saga orchestration, idempotency |
| `risk-service` | 8084 | Real time scoring, analyst review queue, threshold feedback |
| `notification-service` | 8085 | Signed merchant webhooks with retry and dead letter handling |
| `bank-simulator` | 8086 | Fake acquirer that approves, declines, times out and duplicates |
| `common` | n/a | Shared money type, error codes, correlation context. No Spring |
| `common-web` | n/a | Auto configured problem details and correlation id propagation |
| `common-test` | n/a | Integration test harness: containers pinned to the compose images |

`identity-service`, `ledger-service`, `payment-service`, `risk-service` and
`notification-service` each own one database on the shared Postgres, named after the service
without the suffix, and no service connects to another's. `gateway` and `bank-simulator` hold
no state.

Only the gateway is meant to be public. Every service port is published locally for
debugging, and `bank-simulator` is deliberately unreachable through the gateway because
it stands in for a system outside the platform.

Each service is reachable through the gateway under `/api/v1/...`, and its health and API
documentation are reachable at `/internal/<service>/actuator/health` and
`/internal/<service>/v3/api-docs`. Those internal routes are scaffolding for the smoke check
and the documentation browser, and get locked down when authentication lands.

## Design rules

- Money is a `long` of minor units plus an ISO 4217 currency code. No floating point, and
  no `BigDecimal` crossing a service boundary.
- Journal entries are immutable. A correction is a new compensating entry, never an update
  or a delete.
- No service reads another service's tables. Only its API or its events.
- A service owns its schema through forward only migrations applied when it starts. No
  entity generates schema; Hibernate only validates that the migrations built what the code
  expects, so drift fails the service on startup instead of reshaping a live database.
- A merchant is the tenant boundary. Every table that holds money, a payment or a decision
  carries a merchant id, and a user belongs to exactly one merchant.
- A password is stored as a salted bcrypt hash, is never returned by any endpoint and never
  reaches a log line. No response type has a field for one to land in.
- Uniqueness is enforced by the database. A caller finds out an email is taken by the insert
  failing, not by a check that answered a moment earlier.
- Every state change that produces an event writes both in one local transaction, through
  a transactional outbox.
- Every write endpoint accepts an idempotency key and a replay returns the original result.
- Every error is an RFC 9457 problem detail with a stable `code` from a closed enum, and
  the HTTP status is derived from that code so the two cannot disagree.
- Only deliberate errors carry detail. Anything else is an internal error with a fixed
  message, so no stack trace or class name reaches a caller.
- One correlation id per request, generated at the edge if the caller sends none, echoed on
  the response, propagated on outbound calls and across Kafka, and printed on every log
  line. An inbound id is only trusted if it is short and alphanumeric.

## Requirements

Java 21, Docker, Node 20 or newer. Nothing else needs to be installed locally; the
integration tests start Postgres and Kafka in containers through Testcontainers.

## Testing

    ./gradlew build                 # everything, including container backed tests
    ./gradlew build -PfastTests     # skips anything tagged integration, no Docker needed

Integration tests run against real Postgres and real Kafka, never an in memory substitute,
so a test cannot pass on something the deployment does not use. The containers start once
per JVM and are shared across test classes.

`.env` pins the image tags. Docker Compose reads it directly and the Gradle build passes
the same values into the test JVM, so the containers a test starts and the containers
Compose starts cannot drift apart. A test asserts that wiring rather than trusting it.

Runtime budget, measured on a developer machine with the images already pulled and the
compose stack running. A full build serialises every test task, so its wall clock is roughly
the sum of them and moves with whatever else the machine is doing; the stable number
underneath is about 150 seconds of in JVM test time.

| Command | Time |
|---|---|
| `./gradlew build` | three to four minutes |
| `./gradlew build -PfastTests` | about 70 seconds |
| `./gradlew :common-test:test` | about 20 seconds |

Every service owns a database, so proving that a service starts means starting Postgres, and
those tests are tagged integration. `-PfastTests` no longer covers a service starting up.
Test tasks take turns rather than racing each other for the Docker daemon, which is most of
why the full build costs what it does.

If the full build passes five minutes, something has regressed and is worth looking at.

## Migrations

Each service keeps its schema in `src/main/resources/db/migration`, as
`V<number>__<description>.sql`, numbered from one and applied in order by Flyway when the
service starts. A migration that has been applied is never edited; a correction is a new
migration with the next number. The reasoning is in
[ADR 0004](docs/adr/0004-database-per-service-and-migrations.md).

Editing a migration that has already run locally will fail the next startup on a checksum
mismatch. The fix is to throw the local data away rather than repair it:

    docker compose down -v

## API documentation

Every service generates its own OpenAPI specification from the code, and the generated files
are committed under [docs/api](docs/api). A test in each service compares the committed file
against the one the running service produces, so a spec cannot go stale: change an endpoint
without exporting and the build fails. After changing an API, run

    ./gradlew exportOpenApi

and commit what changes. The file is never edited by hand.

The whole platform is browsable in one place. With the stack up, <http://localhost:8080/swagger-ui.html>
lists every service, and a service's own UI is on its own port, `http://localhost:808N/swagger-ui.html`.
`bank-simulator` is absent from the gateway's list on purpose; it stands in for a system
outside the platform and is not routed there.

Errors are part of the contract rather than an afterthought: the problem detail schema and a
response for every `ErrorCode` are contributed to each spec by `common-web`, so an operation
documents a failure by naming the code it can return. Authentication schemes are described
in the spec, and are marked as not enforced until the identity work in MIZ-2 lands.

`identity-service` publishes the first of them: `POST /api/v1/merchants` opens an account,
creating the merchant and its owner in one transaction. Nothing checks who is calling it yet,
so until the gateway starts verifying tokens in MIZ-30 the endpoint is open to anyone who can
reach it.

## Running

    ./gradlew build                    # compile and test
    docker compose up -d --build --wait

That starts Postgres, Kafka, Redis and all seven services, and returns once every one of
them reports healthy. A service that migrates its database waits for a healthy Postgres
first, and reports its datasource in its own health, so a service that cannot reach its
database never reports itself up. The services are skeletons: they start, migrate, report
health and route, but carry no domain logic yet.

## Documentation

Architecture decisions live in [docs/adr](docs/adr). The feature by feature plan is in
[docs/ROADMAP.md](docs/ROADMAP.md). The generated API specifications are in
[docs/api](docs/api).

## License

MIT
