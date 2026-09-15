# ADR 0053: Each merchant has an allowance at the edge, shared by every gateway pod, and Redis being down turns it off

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-88

## Context

The gateway forwarded everything it authenticated. Nothing stopped one merchant with a runaway
retry loop, or a broken integration, from sending as much as the network allowed. Every request
lands on the same payment-service pods, the same database connection pools (ADR 0050) and the same
acquirer allowance (ADR 0052). So one merchant's flood is every other merchant's outage, and ADR
0052 already names this as the case its breaker handles badly: one merchant can open it for all.

The README's service table has said the gateway does rate limiting since the first commit. It did
not.

## Decision

**Every request with a verified merchant spends from that merchant's allowance, kept in Redis so
every gateway pod counts against the same one. A request with nothing left is refused at the edge
with `429 rate-limited` and `Retry-After: 1`. If Redis cannot be reached, nothing is refused.**

- **A token bucket, per merchant, across all routes.** The default refills at 100 requests a
  second and holds up to 200, so a merchant quiet for a moment can burst and a merchant sending
  steadily is held to the rate. One allowance for all routes, because a merchant flooding refunds
  starves others as surely as one flooding payments.
- **Spring Cloud Gateway's own `RedisRateLimiter`**, the bucket updated by one Lua script inside
  Redis. Two pods taking the same merchant's last token at the same instant cannot both spend it.
  Its stock route filter is not used: it refuses with an empty body, not the platform's problem
  detail, so a small `WebFilter` calls the limiter and writes the refusal.
- **Keyed on the merchant the gateway verified**, straight after `AuthenticationFilter` has removed
  any identity headers the caller sent. Keyed on the merchant id in the path, a flood could be
  spread across other merchants' ids and spend their allowances.
- **Public routes are not counted.** Signing in and registering have no merchant yet. Limiting
  those by address is a different problem (credential stuffing) with a different key, not this
  story.
- **Fails open.** The limiter answers "allowed" when Redis does not, and Redis has a 250ms command
  timeout so a hanging Redis costs at most that. A limit that fails closed turns a cache outage
  into every merchant refused everything. Redis is not in the gateway's readiness group, and
  Compose does not make the gateway wait for it.
- **Deployed like the other dependencies.** Compose already ran Redis; the chart now gives the
  gateway its host (`dependencies.redis`), and the kind cluster runs one, which the rollout test
  waits for.
- **Visible.** `mizan_gateway_requests_rate_limited_total`, deliberately with no merchant tag. Which
  merchant is on the log line.

## Evidence

`MerchantRateLimitTest`, a real gateway and a real Redis, limit 5/s with a burst of 10:

- Thirty requests in a row: between 10 and 20 served, the rest refused as a problem detail with
  `code: RATE_LIMITED` and `Retry-After: 1`.
- After waiting a second and a bit, the merchant is served again.
- **Noisy neighbour**: sixteen connections flooding as one merchant, while another sends three a
  second throughout. The flood is mostly refused; **the quiet merchant is never refused**, and its
  slowest request during the flood stays within bounds of its slowest alone.

`RateLimitWithoutRedisTest`: Redis pointed at a port nothing listens on, an allowance of one, twenty
requests: **all twenty served**.

Live against the Compose stack on one laptop, at the default limits, two real merchants reading their
payments: one five times a second, the other from 48 threads as fast as it could.

| | Requests | Refused | Median | p95 |
|---|---|---|---|---|
| Quiet merchant, alone | 50 | 0 | 23ms | 34ms |
| Quiet merchant, during the flood | 50 | **0** | 91ms | 174ms |
| Noisy merchant, 585 a second attempted | 9,024 | 7,324 | 69ms | 161ms |

The flood was held to 110 a second against an allowance of 100 (the burst, spent once). Every refusal
carried `Retry-After: 1` and `code: RATE_LIMITED`, and Prometheus counted exactly 7,324.

## Consequences

- **The limit is per merchant, not per API key or per user.** A merchant's console and their server
  integration share one allowance. That is the unit whose flood hurts others; splitting it would let
  a merchant multiply their allowance by making keys.
- **Starved is not the same as unaffected.** The quiet merchant was never refused, but its median
  latency went from 23ms to 91ms. A refused request is cheap, not free: the gateway still accepts
  the connection, verifies the token, asks Redis and writes the refusal, 585 times a second here,
  and the 110 a second it lets through do real work on the same CPU. On one laptop that is shared by
  everything. The limit bounds what a flood can take from others; it does not make the flood cost
  nothing. More gateway pods, and services on their own nodes, are what spreads the rest.
- **Every limited request costs one Redis round trip** at the edge. MIZ-89's load profiles will put
  a number on that at volume.
- **Load tests run as one merchant.** The kind values raise the limit to 2,000 a second, so the
  rollout test (ADR 0051), about 130 a second from one merchant, measures the rollout rather than
  the limit. It stays on, so the path through Redis is what gets rolled.
- **One allowance for everyone.** A merchant with a genuine need for more has no per-merchant
  override yet. That is a table and an admin endpoint, and nobody has asked.

## Alternatives

- **Per gateway pod, in memory.** No new dependency, but a merchant's real limit becomes the
  configured one times the number of pods, which changes every time the gateway scales. Redis was
  already in the stack and the gateway's library already knows how to use it.
- **Fail closed.** Refuses every merchant whenever Redis blips. The protection is against one
  merchant; losing it briefly is a smaller harm than refusing all of them.
- **Limit in each service.** Every service would need it, and the refused request would already
  have crossed the network and taken a thread. The edge is the one place that sees every request
  first.
- **Resilience4j's rate limiter.** In-process per pod, so the same objection as the in-memory
  option.
