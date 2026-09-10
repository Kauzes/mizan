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
| M1 | Foundation, identity, ledger core, payment happy path | complete |
| M2 | Kafka outbox, risk scoring, refunds and saga compensation | complete |
| M3 | Merchant webhooks, React merchant console | in progress |
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
| `risk-service` | 8084 | Scores a payment and says why, and learns from what analysts rule |
| `notification-service` | 8085 | Turns payment events into what a merchant is told; webhooks next |
| `bank-simulator` | 8086 | Fake acquirer that approves, declines, times out and duplicates |
| `console` | 5173 | The merchant console: React, TypeScript, Vite, served beside the API |
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
- A state change and the event announcing it are written in one transaction, to one
  database, as a row in an outbox. A broker cannot join a database transaction, so publishing
  either way round leaves a window where the money moved and nobody was told, or everybody
  was told about money that never moved. Recording an event outside a transaction is refused
  rather than allowed to quietly give up the only property that matters.
- An event's payload is a record written for consumers, never an entity handed to a
  serialiser. Its envelope carries what every consumer needs whatever the type — id, type,
  version, aggregate, merchant, when, and the correlation id of the request that caused it.
- The events a service publishes are a list it owns, so adding one is a decision rather than
  a string appearing at a call site. A payment intent announces nothing: nobody was contacted
  and no money moved.
- An event is published at least once, and this is said out loud rather than worked around.
  The relay publishes and then marks the row, so a process that dies in between publishes
  again; marking first would lose events instead, which is worse. Every consumer is built for
  repeats.
- Ordering is per aggregate and nothing more is claimed. Every event about one payment carries
  that payment as its key, so they land in one partition in the order they were written.
  Across payments there is no order, because a partitioned log cannot offer one without
  becoming a single partition.
- More than one relay may run. Rows are claimed with `for update skip locked`, and before
  publishing for an aggregate the relay checks that nothing older for it is still unpublished,
  so a later event cannot overtake one another instance is holding.
- An event that will not publish blocks its own payment's later events, necessarily, and no
  other payment's. Retries double to a cap with jitter, and the row keeps the attempt count
  and the last error so a stuck stream can be explained without a log.
- A consumer records that it has handled an event in the same transaction as the handling,
  so the work and the record of it cannot come apart. It resembles the API's idempotency
  records and cannot share an implementation with them: that one commits before the handler
  runs so a concurrent request can wait for its answer, and this one commits with it.
- "Already handled" is a question about a handler, not about a service, so two handlers in
  one service each see the same event.
- A handler that fails is retried a bounded number of times and then the event is set aside
  on a dead letter topic, so that one message nobody can handle blocks nobody. Retrying
  forever and dropping are the two defaults systems reach by accident, and both are wrong.
- A handler may say a failure is hopeless, and then it is not retried at all. A message that
  cannot be parsed will not parse differently in a second.
- Dead letters are read into a table and reachable at `/actuator/deadletters`, keeping the
  reason, the attempt count, the correlation id and the original message byte for byte. An
  operator can send one back, and it goes through the same path as an ordinary delivery.
- One topic per aggregate type, named in one place, declared rather than auto-created. The
  payload's version is on the envelope rather than in the topic name, so a consumer can say it
  does not understand a version instead of silently receiving nothing.
- An authorization posts nothing to the books. It is a promise that the money is there, not
  a movement of it, and the ledger records movements. Capturing is what moves it.
- A timeout is not a decline. The acquirer failing to answer is recorded as not knowing, and
  the outcome is settled by asking the acquirer what it did rather than by guessing either
  way. Nobody has to ask for that: a sweep finds payments nobody knows the outcome of.
- An acquirer with no record of a request is a real answer, meaning nothing happened. Such a
  payment stays unresolved and can be attempted again, rather than being called declined.
- Capturing takes the money, then writes the entry, then marks the payment. Never the other
  order: a payment that says captured with nothing in the books is a lie somebody has to find,
  while an entry with the payment still authorized is one retry from finished. Every step is
  repeatable, so that retry is safe.
- A capture crosses two sets of books, debiting the platform's clearing account and crediting
  the merchant's settlement account, so no merchant-scoped endpoint may write it. It goes to
  an internal route that is outside `/api/`, is not routed to from the edge, and needs a
  credential only the platform's own services hold.
- A void posts nothing. No money moved, and an entry recording a movement that did not happen
  is worse than no entry at all.
- A refund is a new movement, not an undoing. The capture entry stays and the refund is its
  own entry with the opposite postings, naming the one it reverses, so both stay readable.
- A refund is not a state of the payment: a half refunded payment is still captured. The
  payment keeps only the total given back, which is the number the limit is checked against.
- The total refunded can never exceed what was captured, and the payment row is locked before
  that is decided. A limit is only a limit if reading it and writing it are one thing.
- A refund writes down where it has got to before each step, in its own transaction, so a
  process that dies mid flight leaves a record of what was attempted rather than nothing. It
  is then finished from the step it reached, never restarted: the acquirer must not be asked
  twice for money it has already returned.
- "It said no" and "it said nothing" are different facts. Only an outright refusal releases the
  amount a refund reserved; a silence keeps it, because the money may already be gone and
  giving the headroom back is how the same money gets refunded twice.
- A refund nobody can finish is retried a bounded number of times and then left for a person,
  keeping its reservation. Retrying forever is how one broken refund becomes a service doing
  nothing else.
- Giving up is not an answer and is not a state change. A payment the platform has stopped
  trying to resolve is exactly as unknown as it was; what changed is that the platform stopped
  working on it alone, which is a fact about the platform rather than about the money.
- Everything that needs a person is in one place, at `/actuator/stuck`, whatever kind of stuck
  it is, showing what the platform believes and what the acquirer believes side by side —
  asked live, because the reason somebody is looking is that our own record is not to be
  trusted.
- An operator can retry one or record that they have dealt with it, and nothing more. Moving
  money on an operator's say-so would be a way to write the books by hand, which is what a
  double entry ledger exists to prevent.
- A decision records who, when, why, and what it changed, and cannot be rewritten or deleted.
  Evidence the next decision can overwrite is not evidence.
- Risk scoring returns the reasons, not a number. A score of 68 tells a merchant nothing they
  can act on and an analyst nothing they can check; a decision nobody can argue with is one
  nobody can fix.
- Three verdicts, not two. A scorer that can only approve or block has to be certain about
  everything, so it is tuned either to let fraud through or to refuse honest customers. Holding
  a payment for a person costs a delay, which is smaller than either mistake it replaces.
- Signals add up rather than being tested one at a time, and one of them lowers the score: a
  scorer that can only add grows more suspicious of a customer the longer they stay. The total
  is floored at zero so a trusted card cannot bank credit against a later alarming amount.
- Scoring is a pure function of its request, including the timestamp. The same request scores
  the same way twice, so a disagreement about a decision can be reproduced by somebody who was
  not there.
- Authorizing asks risk first, because a payment cannot be scored after it has been
  authorized: the point of scoring is to not authorize it. A block never reaches the acquirer,
  so nobody is contacted and no money is reserved.
- When risk cannot be asked, the payment goes through and is recorded as unscored. Risk is a
  judgement, not a correctness invariant: fraud let through during an outage is bounded and
  recoverable, and refusing every payment for every merchant is neither. UNAVAILABLE is a real
  verdict rather than a null, so a day of them can be reviewed afterwards.
- The risk timeout is shorter than the acquirer's, and there is a circuit breaker in front of
  it. A guard that takes as long as the thing it guards has stopped being a guard, and one
  that costs a timeout per payment while it is down has become the outage.
- A held payment charges nobody and is not a decline. If nobody rules on it, it expires
  refused rather than approved: letting a hold resolve to "take the money" makes the
  safe-looking answer the default and turns a review queue into a delay before approving.
- A held payment is refused an authorization until somebody rules on it, including — especially
  — when the merchant it was applied to simply asks again. The obvious implementation skips
  scoring for a payment that is already held, which is a hole with a queue drawn around it.
- Releasing does not authorize. An analyst says the scorer was wrong about this one; taking the
  money stays something the merchant does, with the card this service does not keep. So a
  released payment is still held and no longer waiting, and the queue and the expiry sweep both
  ask whether anybody has ruled rather than what the status is.
- The scorer learns from rulings, bounded four ways. Three consecutive rulings the same way
  before anything moves and another three before it moves again, five points when it does,
  twenty points of drift at the very most, and a check constraint that says so independently of
  the code. What a person set and what the loop
  inferred are stored separately, so the drift is always visible and always reversible. A loop
  nobody bounded is a loop an attacker teaches, one released payment at a time.
- What is normal is learned from payment events, not typed in. Risk is told what happened and
  never reads the payment database: a scorer that reached across would make the payment service
  unable to change a column without breaking fraud detection.
- The typical amount is a median, not a mean. One car sold by a coffee shop should not redefine
  what a coffee costs, and the outlier a mean would chase is often the fraud itself.
- Only captured payments build a baseline. Declines are remembered, because they say a great
  deal about a card, and kept out of what counts as normal — otherwise a burst of fraud
  attempts teaches the platform that large refused amounts are ordinary here.
- Observations are kept rather than folded into a running total, so a baseline can be rebuilt
  from them. A projection that cannot be rebuilt is a cache with better manners.
- A merchant with no history is not treated as suspicious. Cold start is the common case, and a
  scorer that blocks every merchant's first payment is one nobody switches on.
- A webhook endpoint has to be https and has to resolve to an address on the public internet,
  checked against every address it resolves to rather than against its hostname. A service
  that fetches whatever URL it is handed is a service that makes requests inside its own
  network for whoever asks.
- A webhook signing secret is generated by the platform, shown once, and stored encrypted
  under a different key from the one that opens API key secrets. A compromise of one should
  not be a compromise of the other.
- Rotating a secret takes effect immediately rather than overlapping with the old one. Two
  valid secrets is a rotation that never finishes and an old secret that is never revoked.
- A slow or failing merchant endpoint delays nobody else. Deliveries are claimed with `for
  update skip locked`, the claim commits before the call is made, and every call has a
  timeout. Take any one of those away and one broken merchant stops the platform telling
  anybody anything.
- A delivery's body is built once and stored, not rebuilt per attempt. A signature covers a
  body, and a rebuilt one can differ by a field order — at which point the retry carries a
  signature for something else.
- Every attempt at a delivery is recorded with its response code and how long it took, and a
  merchant can read their own. Keeping only the last answers "is it working now", which is the
  one question they can already answer themselves.
- Every attempt carries the same delivery id, so a merchant who received one and failed to
  answer recognises the repeat rather than counting it twice.
- Only a captured payment can be refunded, and only in the currency it was taken in. This
  platform has no exchange rate, and inventing one to be helpful is how a refund gives back a
  different amount of money than was taken.
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
- A service being down is answered the same way: `UPSTREAM_UNAVAILABLE` when it cannot be
  reached and `UPSTREAM_TIMEOUT` when it stops answering, both with a correlation id and
  neither naming a host. The moment a caller most needs to tell "try again" from "do not"
  is the moment the platform is least able to answer, so it is the moment the contract has
  to hold rather than fall back to the framework's own error body.
- One correlation id per request, generated at the edge if the caller sends none, echoed on
  the response, propagated on outbound calls and across Kafka, and printed on every log
  line. An inbound id is only trusted if it is short and alphanumeric.

## Requirements

Java 21, Docker, and Node 24 or newer for the console. Nothing else needs to be installed
locally; the integration tests start Postgres and Kafka in containers through Testcontainers,
and the console is built inside its own image when the Compose stack comes up.

## Testing

    ./gradlew build                 # everything, including container backed tests
    ./gradlew build -PfastTests     # skips anything tagged integration, no Docker needed
    cd console && npm test          # the console, in jsdom, in about half a minute

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

Above all of it sits [`scripts/smoke.sh`](scripts/smoke.sh), which is not a test task and is
not run by Gradle. It checks what the suite structurally cannot: the services as real
processes, in the images they are deployed as, over a real network, reached through the
gateway. See [Running](#running). Both layers run in CI, and the smoke check does not wait
for the suite — the whole point of it is the failures that leave the suite green.

## Migrations

Each service keeps its schema in `src/main/resources/db/migration/<service>`, as
`V<number>__<description>.sql`, numbered from one and applied in order by Flyway when the
service starts. A migration that has been applied is never edited; a correction is a new
migration with the next number. The reasoning is in
[ADR 0004](docs/adr/0004-database-per-service-and-migrations.md).

The folder is named after the service rather than being the plain `db/migration` every
service used to share, because one classpath is enough to make that ambiguous: a test that
runs two services in one JVM finds two `V1`s and Flyway refuses to start either.

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
| `ANALYST` | Read the merchant and its books, and rule on payments the platform held |
| `VIEWER` | Read the merchant and its books |

An epic that adds endpoints adds the permissions they need and grants them in `Role`, which is
the one place to look when asking what somebody can do. `ANALYST` is deliberately thin:
somebody deciding whether a payment is fraud has no reason to be able to add a user, rotate a
secret or move money, and the point of a separate role is that they cannot.

Beside the roles there is a second question, which only a handful of endpoints ask: whether a
person sent the request, or a merchant's own server holding an API key. `Caller.isPerson()`
answers it. Ruling on a held payment needs a person — a control a merchant can put in a cron
job is not a control.

What each role may do is also served, at `GET /api/v1/roles`, generated from the same enum the
services enforce. The console reads it rather than keeping a copy: a second table would be
right on the day it was typed and wrong on the day somebody adds a permission.

## The console

A React and TypeScript application, built with Vite, served by nginx beside the API rather than
on an origin of its own.

- **A browser is the one client that cannot keep a secret.** The access token lives in a
  closure for the fifteen minutes it is good for. The refresh token lives in a `HttpOnly`,
  `Secure`, `SameSite=Strict` cookie scoped to `/api/v1/tokens`, which the page cannot read.
  Script that reaches the page can use the session while it is open; it cannot copy the
  credential and use it tomorrow. That is ADR 0035, and it is why the console and the API are
  one origin: a cookie only goes back where it came from.
- **One renewal at a time.** A refresh token is single use and replaying one revokes the whole
  family, so two panels refreshing in parallel would look exactly like a stolen token being
  replayed and would sign the person out. The single-flight is not an optimisation.
- **A failed renewal signs the person out**, rather than leaving them holding a token that no
  longer renews and a page that fails differently everywhere.
- **Money is formatted in one place.** The platform transports minor units and never a decimal,
  so nothing else is allowed arithmetic on an amount — including reading one back off a form,
  where multiplying a parsed float by a hundred is how 8.29 becomes 828.
- **The URL is the state.** What a merchant is filtering on lives in the address bar, so a
  search can be sent to somebody and the back button works. Any change to the question returns
  to the first page: staying on page four of a different search shows a blank table to somebody
  who has just narrowed to five results, and reads as "nothing found".
- **An empty answer says which filter emptied it.** A merchant who filtered themselves into
  nothing and one who has never taken a payment otherwise see the same blank table, and only
  one of them has something they can fix.
- **The page that needs three services asks three services.** The payment detail shows the
  timeline, the risk reasons, the ledger entries it produced and the webhook deliveries it
  triggered, and it asks payment-service, ledger-service and notification-service separately.
  A composing endpoint would be one round trip fewer and one service that knows about all
  three, breaking whenever any of them changed. ADR 0036 has the argument.
- **A section nobody may read is not fetched.** A panel that renders a refusal is a panel that
  has told somebody the thing exists.
- Run it with `npm run dev` in `console`, or reach the Compose stack's copy at
  `http://localhost:5173`.

Listing payments takes filters — status, risk verdict, amount range, date range, and the
merchant's own reference — and pages server side. The paging is in headers (`X-Total-Count`,
`X-Page`, `X-Page-Size`, `Link`) rather than an envelope, so the body is the same array it
always was and a client written against the earlier contract is unaffected. A page holds 50 by
default and 200 at most, and paging runs 10,000 deep: an offset is read by counting past every
row before it, so past that the answer is to narrow the search rather than to turn pages.

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

Two commands, from a clean clone, with nothing else installed but Docker:

    docker compose up -d --build --wait     # the whole platform, about three minutes cold
    ./scripts/smoke.sh                      # prove it works

The first starts Postgres, Kafka, Redis and all seven services, and returns only once every
one of them reports healthy. A service that migrates its database waits for a healthy
Postgres first and reports its datasource in its own health, so a service that cannot reach
its database never reports itself up. There is no step after it: no manual migration, no
seeding a key, no waiting and hoping.

The second walks the platform the way a merchant would, through the gateway, and exits non
zero the moment anything is not as it should be. It registers a merchant, signs in, checks
that the same read is refused without a token, opens the settlement account, creates a
payment, authorizes it, checks the books are still untouched, captures it, reads back the
entry and asserts both sides of it, checks that a captured payment can be neither captured
again nor voided, checks that the internal route which crosses into the platform's books is
not reachable from the edge, voids a second payment and confirms the books did not move,
declines a third and confirms the acquirer's reason was kept, and finally asks the ledger to
prove it still balances. It should end with:

      ✓ every currency sums to zero and every balance agrees with its postings

      The platform works end to end.

This is the check the test suite structurally cannot do — real processes, in the images they
are deployed as, over a real network, reached through the gateway. Three defects in this
platform's history were visible only from here: a service whose runtime image lacked a JDK
module the tests had, so it would not start while the suite stayed green; a route the gateway
did not forward; and an idempotency mechanism that was quietly inactive. It runs in CI
against the Compose stack for that reason.

To have something to look at rather than only something that passed:

    ./scripts/seed.sh

That creates two merchants with real books and payments in every state one can be in —
captured, voided, authorized and waiting, declined, a bare intent, and one the acquirer never
answered about, which the sweep resolves a few seconds later. It prints the credentials it
made, so you can sign in as either merchant. The APIs are browsable at
<http://localhost:8080/swagger-ui.html>.

Both scripts need only `curl` and Python, which is why they are shell rather than another
Gradle task: the point is that someone who has not built the project can still run them.

## Documentation

Architecture decisions live in [docs/adr](docs/adr). The feature by feature plan is in
[docs/ROADMAP.md](docs/ROADMAP.md). The generated API specifications are in
[docs/api](docs/api).

## License

MIT
