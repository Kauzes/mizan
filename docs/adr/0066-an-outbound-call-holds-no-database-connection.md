# ADR 0066: An outbound call holds no database connection, and every dependency has a stated limit

- Status: accepted
- Date: 2026-09-22
- Jira: MIZ-105

## Context

Authorizing a payment ran inside one transaction. It read the payment, asked risk, asked the acquirer,
and wrote the answer — holding a database connection for all of it, including the two waits on somebody
else's network. Capturing already did better (ADR 0055): its acquirer call sits between short
transactions. But the last step of a capture still called the ledger from inside one.

MIZ-104 measured what that cost. At thirty payments a second, **47 request threads were queued for a
pool of ten connections** while those threads sat waiting on services that were themselves fast: risk
answered in 19ms at p95, the acquirer in 5ms, the ledger in 93ms. The queue was the platform's entire
latency tail — p95 1,792ms against a p50 of 96ms — and raising the pool to forty moved p95 to 239ms
while taking Postgres to 99 of its 100 connections. A bigger pool was the diagnosis, not the fix.

The connection was doing two jobs, and only one of them was its own: talking to Postgres, and keeping
two authorizations of one payment from interleaving.

## Decision

**No outbound call happens while a database connection is held. What the long transaction was
protecting is protected explicitly instead, and every dependency gets a stated concurrency limit.**

- **Authorizing is four short transactions** with the calls between them: claim, ask risk (record a
  block or a hold and stop), ask the acquirer, record. The capture's shape, for the capture's reason.
- **A mark carries the intent across them.** `authorization_started_at` is written before anybody
  outside is asked and cleared by whatever transition records the answer. A second authorization of the
  same payment finds the mark and is refused with `CONTENDED` rather than sent — which is what the long
  transaction achieved by making the second request wait.
- **A crash between steps leaves the mark**, which says an authorization began and did not finish: the
  same state a timeout leaves, and the one `AuthorizationResolver` already resolves by asking the
  acquirer what it did. Nothing new has to be invented to recover it.
- **The ledger gets a bulkhead.** This is the part that was not planned and is the more interesting
  half. The held connection had been an accidental limit: ten connections meant at most ten captures
  waiting on the books. Removing the hold removed the limit, and the first run without one sent the
  ledger everything at once — **28% of captures failed on its five second timeout, against 0.07%
  before**. So the limit is now stated (`mizan.ledger.max-concurrent-calls`, 12) rather than inherited
  from a pool size, and a full bulkhead refuses with "nothing moved, send it again" instead of a
  timeout that leaves the caller not knowing.
- **ADR 0052's reasoning is corrected, not reversed.** It sized the acquirer bulkhead at eight because
  "each holds a database connection, so this stays below the pool". That sentence is no longer true: the
  number still matters, but as a limit on how many requests may be waiting on a slow dependency, not as
  a way of rationing connections.

## Evidence

**`NothingIsHeldWhileWaitingTest`, new**, which holds the acquirer still mid-authorization and asks the
pool what it is doing:

- **no connection is checked out** while the acquirer is thinking, and the payment authorizes when it
  answers;
- a second authorization of that payment while the first is out is refused with 409 `CONTENDED`, and the
  acquirer is asked **once**;
- the mark does not outlive the attempt that wrote it — a payment that still claimed one was in flight
  would be a payment nobody could authorize again.

**The existing suite, unchanged and passing: 199 tests in payment-service**, including the timeout,
decline, risk-block, risk-hold and resolver paths that the restructure moves across transaction
boundaries.

**The ledger without a limit**: 28% of captures lost to its timeout, which is how the bulkhead came to
be part of this change rather than a follow-up.

**The load measurement is not in yet.** The machine that measured MIZ-104 is now transcoding video at
96% CPU, and every profile run against it — including a rerun of the unchanged code on a fresh database
— reports create alone at three seconds. Numbers from that machine would say nothing about this change.
The before and after belong in `docs/performance` measured on an idle machine, and until they are there
this ADR claims a shape, not a speed.

## Consequences

- **A payment can now be seen mid-authorization.** `authorization_started_at` is set for as long as risk
  and the acquirer take. That is a state a reader of the database will meet, and it is the honest one:
  it was always true, and the transaction merely hid it.
- **Two clients racing one payment get a refusal rather than a wait.** `CONTENDED` says what happened
  and that trying again is safe. A wait would have been kinder to a caller who cannot read, and this
  platform's callers are told to read the code.
- **The ledger's limit is now a number somebody chose**, which means it can be wrong. Twelve is sized
  for thirty payments a second against a ledger answering in under a tenth of a second; a deployment
  with more replicas or a slower ledger wants a different number, and the symptom of it being too low is
  a 503 that says nothing was sent.
- **The connection pool is no longer the platform's concurrency control.** That is the point, and it
  means the limits that remain — the bulkheads — are the ones to look at when something is saturated.

## Alternatives

- **Raise the pool.** Measured: p95 1,792ms to 239ms, and Postgres at 99 of 100 connections. It buys
  latency with the platform's whole connection budget, and the demand it is feeding is a request holding
  a connection to wait on a network.
- **Keep the long transaction and add a queue in front of authorize.** The same waiting, one layer out,
  with the connection still held while it happens.
- **A pessimistic row lock for the duration.** That is the long transaction again, said more explicitly.
- **Nothing at all for the ledger, and let the timeouts be the limit.** A timeout is the caller not
  knowing whether the money moved, and the whole platform is built to avoid arranging that on purpose.
