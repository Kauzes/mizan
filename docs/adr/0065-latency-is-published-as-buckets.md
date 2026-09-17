# ADR 0065: Latency is published as buckets, so the platform can see its own tail

- Status: accepted
- Date: 2026-09-17
- Jira: MIZ-104

## Context

The load profiles (ADR 0054) measured something this platform could not see about itself: at a steady 30
payments a second, a payment completes in 76ms at the median and takes more than a second one time in
twenty. A p95 sixteen times the p50 is not "everything is a bit slow"; it is something stalling
occasionally. `docs/performance` has said since MIZ-89 that this is not yet explained and is the first
thing to look at.

Looking at it exposed the more basic problem. Every service published `http_server_requests_seconds` as a
count, a sum and a max. A count and a sum give a mean, and **a mean hides a tail by construction**: one
request in twenty taking two seconds moves a mean by a tenth of a second. A max is one request, which is
as likely to be a container warming up as a problem. So the tail existed only in k6's output, measured
from outside, during a load run — and a number that can only be seen during a test is a number that
regresses quietly between tests.

## Decision

**Every service publishes HTTP latency as histogram buckets, contributed once for the whole platform, at
seven boundaries chosen to bracket what the load profiles claim.**

- **Buckets, not client-side quantiles.** Micrometer can publish a precomputed p95 per instance. Those
  cannot be added up: the p95 of four instances is not the mean of their p95s, and a dashboard that
  averages them is confidently wrong. Buckets sum, so `histogram_quantile` computes the real thing across
  instances.
- **Seven boundaries: 50ms, 100ms, 250ms, 500ms, 800ms, 2s, 5s.** They bracket the claims the profiles
  hold the platform to — p95 under 500ms to create a payment, under 800ms to authorize or capture — with
  two more past them to show what breaks a claim. The default exponential set is dozens of series per
  endpoint per instance, and latency is worth counting, not worth counting sixty ways.
- **Bounded at 5ms and 10s.** Below is noise; above it, the request has already failed a timeout
  somewhere else.
- **Contributed like everything else this platform defaults**: an environment post-processor in
  `common-web`, at the lowest precedence, so a service with a reason to differ says so and wins. A new
  service gets it by existing, in the same way it gets its log format and its shutdown behaviour.
- **Asserted in the smoke check**, not only in a unit test: the check asks the running Prometheus for a
  p95 and fails if it cannot be computed. A configuration that is right in the source and absent from the
  deployed image is exactly the class of failure the smoke check exists for.

## Evidence

- `WhatEveryServiceMeasuresTest` (4): buckets are published, the boundaries bracket what the profiles
  claim and stay under ten, nothing is measured below 5ms or above 10s, and a service overriding the
  boundaries still wins.
- The smoke check computes a p95 from the running platform's buckets and fails if it cannot.
- A panel on *is the platform up?* shows p95 and p99 per service, next to the maximum that was there
  before — the two answer different questions and both are worth having.

## Consequences

- **More series.** Seven buckets plus `+Inf` per endpoint, method, status and service. That is the cost
  of being able to ask the question at all, and it is bounded: the smoke check already fails on labels
  with no upper bound, and none of these are per merchant or per payment.
- **The tail is now visible outside a load test**, which is the point. What it is caused by is a separate
  question, and this platform's answer to it is written in `docs/performance` rather than here.
- **Percentiles from buckets are approximate**, to the width of the bucket the answer falls in. A p95 of
  "between 500ms and 800ms" is what this needs; an exact figure to the millisecond is not a thing a
  distributed platform can honestly report anyway.
- **Existing panels and alerts are untouched.** `_max` still exists and still means what it meant.

## Alternatives

- **Publish precomputed percentiles** (`management.metrics.distribution.percentiles`). Cheaper, and
  wrong the moment there is more than one instance.
- **Leave it to k6.** It measures the right thing, from the right side, and only while somebody is
  running it. That is a test, not an observation.
- **The default exponential buckets.** No decision to make, and dozens of series per endpoint where seven
  boundaries answer every question this platform asks.
- **Trace-derived latency (span metrics in the collector).** A real option, and a bigger one: it moves
  the measurement into the collector's configuration, and this platform samples traces, so its tail would
  be a sample of a tail.
