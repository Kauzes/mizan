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
| `common` | n/a | Shared money type, event schemas, error model, correlation context |

Only the gateway is meant to be public. Every service port is published locally for
debugging, and `bank-simulator` is deliberately unreachable through the gateway because
it stands in for a system outside the platform.

Each service is reachable through the gateway under `/api/v1/...`, and its health is
reachable at `/internal/<service>/actuator/health`. Those internal routes are scaffolding
for the smoke check and get locked down when authentication lands.

## Design rules

- Money is a `long` of minor units plus an ISO 4217 currency code. No floating point, and
  no `BigDecimal` crossing a service boundary.
- Journal entries are immutable. A correction is a new compensating entry, never an update
  or a delete.
- No service reads another service's tables. Only its API or its events.
- Every state change that produces an event writes both in one local transaction, through
  a transactional outbox.
- Every write endpoint accepts an idempotency key and a replay returns the original result.

## Requirements

Java 21, Docker, Node 20 or newer. Nothing else needs to be installed locally; the
integration tests start Postgres and Kafka in containers through Testcontainers.

## Running

    ./gradlew build                    # compile and test
    docker compose up -d --build --wait

That starts Postgres, Kafka, Redis and all seven services, and returns once every one of
them reports healthy. The services are skeletons: they start, report health and route, but
carry no domain logic yet.

## Documentation

Architecture decisions live in [docs/adr](docs/adr). The feature by feature plan is in
[docs/ROADMAP.md](docs/ROADMAP.md).

## License

MIT
