# Decisions

Every architecture decision in this platform, newest last. Generated from the ADRs themselves by
`./scripts/adr-index.sh`, and checked by `AdrIndexTest`, so a decision recorded without an entry here
fails the build.

A decision that was later narrowed, superseded or revisited says so in its own header and in the
**Changed since** column, naming what changed it and why. None of them are deleted: what was decided,
and later decided differently, is the part worth reading.

| ADR | Decision | Story | Status | Changed since |
|---|---|---|---|---|
| 0001 | [Split into services rather than a modular monolith](0001-service-boundaries.md) | MIZ-1 | accepted | — |
| 0002 | [Money is minor units in a long](0002-money-representation.md) | MIZ-1 | accepted | — |
| 0003 | [RFC 9457 problem details with a stable code, and one correlation id per request](0003-error-model-and-correlation.md) | MIZ-22 | accepted | — |
| 0004 | [A database per service, with the schema owned by forward only migrations](0004-database-per-service-and-migrations.md) | MIZ-23 | accepted | — |
| 0005 | [The API contract is generated from the code, and committed](0005-api-contract-generated-and-committed.md) | MIZ-25 | accepted | — |
| 0006 | [The merchant is the tenant boundary, and how a password is stored](0006-merchant-tenancy-and-password-storage.md) | MIZ-28 | accepted | — |
| 0007 | [Access tokens are signed asymmetrically, and refresh tokens rotate](0007-access-tokens-and-refresh-rotation.md) | MIZ-29 | accepted | — |
| 0008 | [Authentication happens at the edge, and identity travels on headers](0008-authentication-at-the-edge.md) | MIZ-30 | accepted | — |
| 0009 | [Roles carry permissions, and the tenant boundary is checked before the handler](0009-roles-permissions-and-tenant-isolation.md) | MIZ-31 | accepted | — |
| 0010 | [Merchant servers sign requests, and their secrets are encrypted rather than hashed](0010-api-keys-and-request-signing.md) | MIZ-32 | accepted | Revisited: 2026-09-01, after review. HMAC confirmed; ciphertext bound to its key row. |
| 0011 | [What an account is, and who is allowed to have one](0011-chart-of-accounts.md) | MIZ-34 | accepted | — |
| 0012 | [The journal is append only, and balances by arithmetic the database checks](0012-the-journal-and-its-invariant.md) | MIZ-35 | accepted | — |
| 0013 | [A posting carries a reference, and a retry is answered with the first result](0013-idempotent-posting.md) | MIZ-36 | accepted | — |
| 0014 | [A balance is kept on the account, and guarded by a version](0014-balances-kept-and-contended.md) | MIZ-37 | accepted | Superseded in part: 2026-09-01 by MIZ-39, which replaced the optimistic retry with a row lock. The measurement that forced it is below. |
| 0015 | [The ledger is asked to prove itself, by something that shares none of its assumptions](0015-the-ledger-proves-itself.md) | MIZ-38 | accepted | — |
| 0016 | [A payment is a state machine, written down before anything moves it](0016-the-payment-state-machine.md) | MIZ-40 | accepted | — |
| 0017 | [Every write says what a repeat of it does](0017-idempotency-key-across-the-platform.md) | MIZ-41 | accepted | — |
| 0018 | [The acquirer is driven by test cards, not by a switch](0018-an-acquirer-that-can-be-told-how-to-behave.md) | MIZ-42 | accepted | — |
| 0019 | [An authorization is a promise, and promises are not posted](0019-authorization-posts-nothing.md) | MIZ-43 | accepted | — |
| 0020 | [A timeout is resolved by asking, never by assuming](0020-a-timeout-is-resolved-by-asking.md) | MIZ-44 | accepted | — |
| 0021 | [A capture crosses two sets of books, so it is not a merchant's to write](0021-capture-crosses-two-sets-of-books.md) | MIZ-45 | accepted | — |
| 0022 | [An event is written in the transaction that caused it](0022-an-event-is-written-in-the-transaction-that-caused-it.md) | MIZ-47 | accepted | — |
| 0023 | [Events are published at least once, and in order per aggregate](0023-events-are-published-at-least-once-and-in-order-per-aggregate.md) | MIZ-48 | accepted | — |
| 0024 | [A consumer that sees an event twice acts once](0024-a-consumer-that-sees-an-event-twice-acts-once.md) | MIZ-49 | accepted | — |
| 0025 | [An event nobody can handle blocks nobody, and is visible to somebody](0025-an-event-nobody-can-handle-blocks-nobody.md) | MIZ-50 | accepted | — |
| 0026 | [A refund is a new movement, not an undoing](0026-a-refund-is-a-new-movement-not-an-undoing.md) | MIZ-51 | accepted | — |
| 0027 | [A refund is a saga that resumes, not a transaction that cannot](0027-a-refund-is-a-saga-that-resumes.md) | MIZ-52 | accepted | — |
| 0028 | [A payment nobody can finish is somebody's problem, not the platform's secret](0028-a-payment-nobody-can-finish-is-somebodys-problem.md) | MIZ-53 | accepted | — |
| 0029 | [A webhook endpoint is a URL somebody else chose](0029-a-webhook-endpoint-is-a-url-somebody-else-chose.md) | MIZ-54 | accepted | — |
| 0030 | [A slow merchant slows only themselves](0030-a-slow-merchant-slows-only-themselves.md) | MIZ-55 | accepted | — |
| 0031 | [A score that cannot say why is a decision nobody can fix](0031-a-score-that-cannot-say-why-is-a-decision-nobody-can-fix.md) | MIZ-56 | accepted | — |
| 0032 | [What is normal is learned, not typed in](0032-what-is-normal-is-learned-not-typed-in.md) | MIZ-57 | accepted | — |
| 0033 | [Risk is a guard, not an invariant, so the platform fails open](0033-risk-is-a-guard-not-an-invariant.md) | MIZ-58 | accepted | — |
| 0034 | [A loop that learns from people has to be bounded, slow, and reversible](0034-a-bounded-loop-is-the-only-safe-kind.md) | MIZ-59 | accepted | — |
| 0035 | [A browser is the one client that cannot be trusted to store a refresh token](0035-a-browser-is-the-one-client-that-cannot-keep-a-secret.md) | MIZ-60 | accepted | — |
| 0036 | [The page that needs three services asks three services](0036-the-page-composes-rather-than-a-service.md) | MIZ-62 | accepted | — |
| 0037 | [Settlement is its own service, because it is about days rather than payments](0037-settlement-is-about-days-not-payments.md) | MIZ-69 | accepted | — |
| 0038 | [Reconciliation reports a difference, it does not correct one](0038-reconciliation-reports-it-does-not-correct.md) | MIZ-72 | accepted | — |
| 0039 | [A difference leaves the queue only when a person decides](0039-a-difference-leaves-the-queue-only-when-a-person-decides.md) | MIZ-73 | accepted | — |
| 0040 | [The gateway's actuator moves to a port the edge does not serve](0040-the-gateways-own-numbers-are-not-served-at-the-edge.md) | MIZ-75 | accepted | — |
| 0041 | [No metric carries a merchant, a payment or an amount](0041-no-metric-is-labelled-with-a-merchant.md) | MIZ-76 | accepted | — |
| 0042 | [The dashboard is the file, and the browser is a view of it](0042-the-dashboard-is-the-file.md) | MIZ-77 | accepted | — |
| 0043 | [Every service traces everything, and one collector decides what is kept](0043-the-collector-decides-which-traces-are-kept.md) | MIZ-78 | accepted | — |
| 0044 | [One place shapes a log line, and a card never appears on one](0044-a-card-is-never-written-down.md) | MIZ-79 | accepted | — |
| 0045 | [An alert names what a person does, and is made to fire before it is trusted](0045-an-alert-names-what-a-person-does.md) | MIZ-80 | accepted | — |
| 0046 | [Service images are distroless, and the healthcheck is the JVM already in them](0046-images-are-distroless-and-probed-by-the-jvm.md) | MIZ-81 | accepted | — |
| 0047 | [CI publishes the image it tested, and a known fixable hole stops it](0047-ci-publishes-the-image-it-tested.md) | MIZ-82 | accepted | — |
| 0048 | [One Helm chart installs the services, and points at what it does not own](0048-one-chart-that-points-at-its-dependencies.md) | MIZ-83 | accepted | — |
| 0049 | [Starting, ready and alive are three questions, and a pod leaves before it stops](0049-three-questions-and-a-pod-that-leaves-before-it-stops.md) | MIZ-84 | accepted | — |
| 0050 | [Payment service scales on what it runs out of, inside a connection budget](0050-payment-service-scales-on-what-it-actually-runs-out-of.md) | MIZ-85 | accepted | — |
| 0051 | [A rollout is tested while money moves, against a budget written first](0051-a-rollout-is-tested-while-money-moves.md) | MIZ-86 | accepted | — |
| 0052 | [A slow or broken acquirer costs a bounded amount, and refusals send nothing](0052-a-slow-acquirer-costs-a-bounded-amount.md) | MIZ-87 | accepted | — |
| 0053 | [Each merchant has an allowance at the edge, shared by every gateway pod, and Redis being down turns it off](0053-one-merchant-cannot-starve-the-others.md) | MIZ-88 | accepted | — |
| 0054 | [Load is offered at a fixed arrival rate, spread across merchants, and the numbers are written down with the machine](0054-load-is-measured-at-a-fixed-arrival-rate-and-written-down.md) | MIZ-89 | accepted | — |
| 0055 | [A capture writes down that it began, and a sweep finishes what nobody finished](0055-a-capture-writes-down-that-it-began.md) | MIZ-90 | accepted | — |
| 0056 | [An unreachable broker is visible before anybody downstream misses it](0056-an-unreachable-broker-is-visible-before-it-is-missed.md) | MIZ-91 | accepted | — |
| 0057 | [The merchant app is its own build, shaped like Sentinel Pay, and talks only to the gateway](0057-the-merchant-app-is-its-own-build.md) | MIZ-97 | accepted | — |
| 0058 | [The phone holds its own session, encrypted, and renews it once](0058-the-phone-holds-its-own-session.md) | MIZ-98 | accepted | — |
| 0059 | [A phone payment is written down before it is sent, and never with the card](0059-a-phone-payment-is-written-down-before-it-is-sent.md) | MIZ-99 | accepted | Narrowed: 2026-09-17 by ADR 0060, which lets a payment queued with no signal keep its card, encrypted and for at most a day, until its authorization is answered. |
| 0060 | [A queued payment keeps its card, encrypted, and only until it is answered](0060-a-card-waits-only-as-long-as-the-payment-does.md) | MIZ-100 | accepted | — |
| 0061 | [The phone asks, because nothing here can tell it](0061-the-phone-asks-rather-than-being-told.md) | MIZ-101 | accepted | — |
| 0062 | [One review queue, two clients, no phone-shaped copy of it](0062-one-review-queue-two-clients.md) | MIZ-102 | accepted | — |
| 0063 | [The architecture diagram is generated from source and checked against the platform](0063-the-picture-is-checked-against-the-platform.md) | MIZ-92 | accepted | — |
| 0064 | [The worked example is a recording of the platform, not a description of it](0064-the-example-is-a-recording-not-a-description.md) | MIZ-94 | accepted | — |
| 0065 | [Latency is published as buckets, so the platform can see its own tail](0065-latency-is-published-as-buckets.md) | MIZ-104 | accepted | — |

65 decisions. The template for a new one is [0000-template.md](0000-template.md).
