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
`/internal/<service>/v3/api-docs`. Those two are all that the internal routes still expose
without a token: a published contract is documentation and a liveness probe holds no
credentials, while anything else under `/internal/**` now needs an access token like any
other protected route.

## Design rules

- Money is a `long` of minor units plus an ISO 4217 currency code. No floating point, and
  no `BigDecimal` crossing a service boundary.
- Journal entries are immutable, and the database says so: an update or a delete against the
  journal raises. A correction is a new entry naming the one it corrects, so both stay
  visible.
- The ledger can be asked to prove it has not drifted: every currency sums to zero and every
  kept balance agrees with its own postings. Reachable at `/actuator/ledgerintegrity`, which
  needs a token through the gateway. It reports what disagreed and by how much rather than
  repairing anything, because a balance that disagrees with its postings is evidence.
- A balance is kept on the account and written in the same transaction as the postings that
  move it, so reading one is a single row however long the history is. A version column
  refuses a lost update, and the write is retried rather than handed back.
- An entry's postings sum to zero within each currency they touch, checked in the domain and
  again by a deferred constraint trigger. The second one is what holds against anything that
  writes to the table without going through the service.
- An account carries one currency and one type, both fixed for its life. The type is what
  decides whether a debit makes the balance larger, so a caller never says which way an
  account moves and cannot say it wrongly.
- A merchant's balance is a liability of the platform. The money is the merchant's, held by
  Mizan, so paying it out reduces what is owed.
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
- A merchant's server authenticates with a key and a signature, not a bearer token. The
  signature covers the method, the path, the body and a timestamp, so a captured request
  cannot be replayed, altered, or aimed somewhere else.
- Authentication happens once, at the gateway. A service behind it receives a caller who
  has already been established, on headers the gateway sets after stripping whatever the
  caller sent under the same names.
- What is public is a list, not a pattern. Forgetting to add a route to it produces a 401,
  which is the failure that gets noticed rather than the one that does not.
- An endpoint declares the permission it needs, and a service refuses to start if one under
  `/api/` declares nothing. Forgetting is invisible otherwise: the endpoint works, which is
  what it also looks like when it is correct.
- Where a path names a merchant, the caller must be acting for that merchant. Checked before
  the handler runs, so an endpoint is guarded by its path rather than by somebody
  remembering.
- A refusal says nothing about whether the thing refused exists. Another merchant's data and
  data that was never created are answered identically.
- An access token is verified by signature, issuer and expiry alone. No service asks
  identity who a caller is, so identity is not on the path of every payment.
- Identity signs with a private key and publishes the public half. Whoever verifies a token
  should not be able to mint one, least of all the component facing the internet.
- Refresh tokens are single use. Presenting a spent one revokes every token descended from
  that sign in, because a replay and a theft cannot be told apart.
- Every state change that produces an event writes both in one local transaction, through
  a transactional outbox.
- A payment moves through a state machine written down in one place, only ever forwards, and
  every step is recorded in a history the database refuses to let anybody rewrite. An illegal
  transition is refused in terms of the two states rather than as a generic error.
- Every write under `/api/` says what a repeat of it does, with `@Idempotent` or
  `@NotIdempotent` and a reason, and a service refuses to start if one says neither. An
  idempotent write needs an `Idempotency-Key`; sending the same one again returns what the
  first call produced, with the same status, and the same key with a different body is
  refused.
- Every write endpoint accepts an idempotency key and a replay returns the original result.
  In the ledger that key is the entry's external reference: required, unique per merchant,
  and answered on a retry with the first call's entry and the first call's status, so a
  client that timed out cannot tell its retry from the original. The same reference sent
  with different postings is refused rather than quietly answered.
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

What it does is decided by the last four digits of the card it is given, so any outcome can be
provoked without the platform knowing it is talking to a simulator: `0002` declines for
insufficient funds, `0005` for do not honour, `0007` for a stolen card, `0069` approves but
withholds the answer for longer than the caller will wait, and anything else approves. Its
catalogue is in [its own spec](docs/api/bank-simulator.yaml).

Errors are part of the contract rather than an afterthought: the problem detail schema and a
response for every `ErrorCode` are contributed to each spec by `common-web`, so an operation
documents a failure by naming the code it can return. Authentication schemes are described
in the spec. The bearer token is enforced by the gateway, and an operation that needs one
says so; the API key pair is still description, and says so, until MIZ-32.

`identity-service` publishes the first of them. `POST /api/v1/merchants` opens an account,
creating the merchant and its owner in one transaction. `POST /api/v1/tokens` exchanges that
owner's email and password for an access token and a refresh token, and `POST
/api/v1/tokens/refresh` rotates the pair. The public key access tokens are signed with is at
`/.well-known/jwks.json`.

The gateway verifies that token on every route that is not on its public list, and passes the
established caller downstream on `X-Mizan-User`, `X-Mizan-Merchant` and `X-Mizan-Roles`,
having first removed whatever arrived under those names. A service reads them and does not
check anything itself.

A service acts on that identity rather than trusting the caller. Every endpoint under
`/api/` declares the permission it needs, and where the path names a merchant, a caller acting
for a different one is refused before anything is looked up — identically whether that
merchant exists or not.

| Role | May |
|---|---|
| `OWNER` | Everything, within their own merchant. Adding and removing people, changing what they may do, and issuing API keys |
| `ADMIN` | Read the merchant, see who acts for it, and open accounts |
| `ANALYST` | Read the merchant and its books. The review queue in MIZ-6 is what this role is for |
| `VIEWER` | Read the merchant and its books |

An epic that adds endpoints adds the permissions they need and grants them in `Role`, which is
the one place to look when asking what somebody can do. `ANALYST` is still thinner than it will
be; the review queue in MIZ-6 is what that role is for.

A merchant always has an owner: the last one cannot be removed or demoted. An account nobody
can administer is recoverable only by hand in the database.

### Server to server

A merchant's own servers use an API key instead of signing in. An owner issues one at
`POST /api/v1/merchants/{merchantId}/api-keys`, which returns the signing secret once and never
again; the stored copy is encrypted, and the key that opens it is configuration rather than a
row in the database. Each key carries one role, and rotating a key issues its replacement and
revokes it in the same step.

A signed request carries three headers. `X-Mizan-Key` names the key, `X-Mizan-Timestamp` is the
unix second it was signed, and `X-Mizan-Signature` is HMAC-SHA256, in lowercase hex, of four
lines joined by newlines: the uppercase method, the path, that timestamp, and the SHA-256 of the
body. The spec carries the same definition with a worked example, which is the copy to write a
client from.

Every signed request is verified by identity rather than at the edge, so revoking a key takes
effect on the next request rather than when a cache expires.

Locally the service generates its signing key at startup and warns that it did. That means
tokens stop working when it restarts, which is the point: a default key is either obviously
local or it quietly becomes the key a deployment runs on. Set `MIZAN_JWT_PRIVATE_KEY` to a
PKCS#8 PEM anywhere that matters.

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
