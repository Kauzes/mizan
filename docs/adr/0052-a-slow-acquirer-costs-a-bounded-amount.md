# ADR 0052: A slow or broken acquirer costs a bounded amount, and refusals send nothing

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-87

## Context

Until now the acquirer was guarded by a timeout and nothing else. Two ways that fails:

- **The acquirer is down.** Every authorization waits out the full timeout before failing. At ten
  payments a second and a five second timeout, each second adds fifty seconds of waiting. Risk had
  a breaker for exactly this reason (ADR 0033); the acquirer, the more important dependency, did
  not.
- **The acquirer is slow but answering.** A breaker never opens, because nothing fails. But an
  authorization holds a request thread *and a database connection* for as long as it waits (ADR
  0050), and the chart gives each pod a pool of five. Five slow authorizations take the pool, and a
  merchant merely reading a payment then waits behind the bank for a connection.

There is a constraint neither of those has for risk: **a call to the acquirer that was sent and not
answered leaves the money in an unknown state**, which starts a resolution (MIZ-44, MIZ-52). Any
guard added here must fail in a way that leaves nothing unknown.

## Decision

**Every acquirer call goes through a bulkhead and then a breaker, and both refuse without sending.
A refusal is `503 upstream-unavailable`, never `504 upstream-timeout`.**

- **The bulkhead** lets a fixed number of calls wait on the acquirer at once: 8 by default, **3 in
  the chart**, below the pool of 5. A call beyond that waits up to 100ms for a turn, so an ordinary
  burst to a healthy acquirer, overlapping for milliseconds, is not refused. `ChartTest` fails if
  the limit reaches the pool.
- **The breaker** opens after 10 failures *in a row* and leaves the acquirer alone for 10 seconds,
  then lets a call through to find out.
- **A refusal is an answer.** A 4xx from the acquirer, such as a capture of a voided authorization,
  is the acquirer working and resets the count rather than adding to it. A decline arrives as a
  success. So a run of merchants sending cards that are refused cannot stop the platform asking.
- **Not sent is not unknown.** The existing split does the rest: `UPSTREAM_TIMEOUT` records an
  unknown outcome and starts a resolution; `UPSTREAM_UNAVAILABLE` does not. An authorization never
  sent stays `CREATED`. A refund never sent keeps its reservation and the sweep asks again, as it
  already did when the acquirer could not be reached. A look-up never sent leaves the payment
  unresolved for the next sweep.
- **Visible.** `mizan_acquirer_breaker_open` (0 or 1), `mizan_acquirer_calls_in_flight`, and
  `mizan_acquirer_calls_not_sent_total{because="breaker_open"|"too_many_waiting"}`.

## Evidence

`AcquirerUnderPressureTest`, against an acquirer the test controls:

- Broken: three payments reach it, the breaker opens, and **ten more cost it nothing**, each
  answered 503 saying nothing was sent.
- Slow: three timeouts, then the fourth is refused **in well under the timeout** and the payment is
  still `CREATED`, not unknown.
- Recovered: after the quiet period it is asked again and the payment authorizes.
- Refusing: six captures refused with 422, twice the threshold, **every one sent**, breaker closed.
- Hanging: two calls wait on it; a third is refused and not sent, and **a read of another payment
  answers** while they hang.

## Consequences

- **The breaker is per pod and per acquirer, not per merchant.** One merchant's traffic can open it
  for everyone on that pod. That is correct when the acquirer is failing and wrong only if one
  merchant's requests alone make the acquirer fail, which a per-merchant rate limit (MIZ-88) is the
  better answer to.
- **Failures in a row, not a failure rate.** Under heavy traffic with a partly failing acquirer,
  interleaved successes keep resetting the count, so the breaker opens later than a rate-based one
  would. Kept simple on purpose; revisit if MIZ-89's load profiles show it matters.
- **A merchant sees a 503 they may retry** instead of waiting five seconds for a 504 they must
  resolve. Every write here is idempotent, so a retry is always safe.
- The bank simulator's slow cards open the breaker too. Ten slow cards in a row locally will leave
  the acquirer alone for ten seconds, which is the behaviour, not a bug in the demo.

## Alternatives

- **Resilience4j**, which the epic names. It is the standard choice, and would give rate-based
  windows and a bulkhead in one dependency. ADR 0033 already chose a sixty-line hand-written breaker
  over it, because of how often Boot 4's module splits have left integrations behind here. The
  bulkhead is a semaphore. Together they are under two hundred lines with their own tests, which
  keeps that decision rather than running two resilience approaches side by side. If rate windows
  or retry policies are ever needed, that is the point to switch, all at once.
- **Only a breaker.** Does nothing about slow-but-answering, which is the case that takes the
  connection pool.
- **Only a shorter timeout.** Every timeout is an unknown outcome to resolve. Shorter timeouts make
  more of them; refusing before sending makes none.
- **A bulkhead with no wait.** Refuses healthy bursts. A hundred milliseconds costs nothing when
  the acquirer is fine and only delays a refusal that was coming anyway when it is not.
