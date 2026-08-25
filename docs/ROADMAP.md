# Roadmap

Features land one Jira issue at a time on the `MIZ` board. Each milestone ends with a
system that runs and can be demonstrated, not a half wired layer.

## M1 Foundation

- **Epic 1 Foundation.** Gradle multi module monorepo, `common` module, Compose stack for
  Postgres, Kafka and Redis, CI running build and test, ADR and PR templates.
- **Epic 2 Identity and access.** Merchants, users, roles, JWT access and refresh tokens,
  merchant API keys with HMAC request signing, gateway auth filter.
- **Epic 3 Ledger core.** Chart of accounts, immutable journal entries and postings, the
  zero sum invariant enforced in the domain and in the database, balance reads, optimistic
  locking, a concurrency test that proves no lost update.
- **Epic 4 Payment lifecycle.** Payment intent, authorize, capture, void, an explicit state
  machine, and idempotency keys so a replayed request returns the original result.

## M2 Event driven and failure tolerant

- **Epic 5 Event backbone.** Kafka topics, transactional outbox and relay, event versioning,
  idempotent consumers, dead letter queue, replay tooling.
- **Epic 6 Risk and review.** Real time scoring in the payment flow with a timeout policy,
  an analyst review queue, and analyst rulings feeding back into thresholds.
- **Epic 7 Refunds and saga compensation.** Full and partial refunds, compensating ledger
  entries, a saga that survives a crash mid flight, and a failure injection test that kills
  a service between steps and asserts the books still balance.

## M3 Something to look at

- **Epic 8 Merchant webhooks.** HMAC signed payloads, exponential backoff with jitter,
  delivery log, dead letter queue, manual redelivery.
- **Epic 9 Merchant console.** React and TypeScript: payments list and detail with the event
  timeline, refunds, ledger explorer, risk review queue, API keys, webhook log, dashboards.

## M4 Money that adds up

- **Epic 10 Settlement and reconciliation.** Daily settlement batches, merchant payout
  accounts, reconciliation against the simulated bank statement, mismatch reporting, and a
  global integrity check asserting every posting in the system sums to zero.
- **Epic 11 Observability and ops.** Metrics including business metrics, Grafana dashboards
  in the repo, distributed tracing end to end, structured logs with correlation ids, health
  and readiness endpoints, alert rules.

## M5 Delivery and proof

- **Epic 12 Containers and Kubernetes.** Multi stage distroless images, Helm chart, probes,
  resource limits, autoscaling, a verified rolling deploy on kind, image scanning in CI.
- **Epic 13 Performance and resilience.** Load profiles, circuit breakers and bulkheads
  around the acquirer, gateway rate limiting, a chaos test that kills the ledger mid saga,
  and a benchmark whose numbers are recorded in the README.

## M6 Mobile and packaging

- **Epic 14 Android merchant app.** Compose UI, Room offline queue with sync and conflict
  handling, push on a flagged payment, approve or decline from the phone, APK built in CI.
- **Epic 15 Documentation.** Architecture diagrams, the ADR set, a demo recording, and a
  seeded demo dataset.
