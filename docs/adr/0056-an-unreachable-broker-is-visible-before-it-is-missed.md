# ADR 0056: An unreachable broker is visible before anybody downstream misses it

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-91

## Context

The outbox (ADR on MIZ-47 and MIZ-48) exists so that a broker outage costs a merchant nothing at the
moment of paying: a payment and the event announcing it commit together in one database, and a relay
publishes the event whenever the broker will take it. That works, and it is also exactly why a broker
outage is silent. Every payment is accepted. What stops is everything downstream of the event:
webhooks, notifications, settlement hearing about captures.

Before this story nothing measured that. No metric counted unpublished events, no alert existed, and
the only sign was a warning per failed attempt in payment-service's log. A broker down for an hour
looked, from anything an operator watches, like a quiet hour.

Reading the relay while preparing the chaos test also raised a question it had to answer rather than
assume: `KafkaEventPublisher` bounds the wait for an acknowledgement at 10 seconds, but nothing sets
the producer's `max.block.ms`, so `send()` itself may block for Kafka's default of 60 seconds while it
waits for metadata, inside the transaction that holds the relay's claimed rows.

## Decision

**payment-service measures what is waiting to leave and how long the oldest of it has waited, an alert
pages when events have stopped leaving, and a chaos test cuts Kafka off under load and fails unless
payments keep being accepted, the backlog is visible, and every event reaches its consumer exactly
once afterwards.**

- **Two gauges**, `mizan_outbox_waiting` and `mizan_outbox_oldest_waiting_seconds`, refreshed on the
  same 15 second timer as the other operator numbers in `PaymentMetrics`, never by a query on the
  scrape thread. In payment-service rather than common-web, because it is the one service that
  publishes from an outbox and common-web carries no metrics dependency.
- **The age is what pages, not the count.** A busy minute produces a large count that drains in a
  second; a broker outage produces an age that climbs a minute every minute. The count is for the
  dashboard, the age is for the pager.
- **`EventsHaveStoppedLeaving`**: oldest unpublished event older than 5 minutes, for 5 minutes. A pass
  normally publishes within a second, so 5 minutes is not slowness, and 5 more keeps a broker restart
  from paging. Tested with `promtool` both ways: a two minute backlog that drains stays quiet, an
  outage fires.
- **The chaos test pauses the Kafka container**, rather than stopping it. A paused broker neither
  accepts nor refuses; connections hang, which is how a partition looks to a producer and is harder on
  a client than a refusal. Nothing else is touched, so payment-service keeps its database.
- **"Every event arrived once" is checked at the consumer**: every outbox event written during the run
  must be published, must appear in notification-service's `handled_event` for its handler, and must
  appear there only once.

## Evidence

`OutboxRelayTest`: an event the publisher refuses is counted as waiting (1) and its age rises while it
waits; once it publishes, both gauges return to 0. The first version aged the event by editing its
`occurred_at`, and the database refused: `outbox_event_is_append_only` allows no change but
`published_at`. It now ages by waiting.

`scripts/chaos-kafka.sh` on the Compose stack, steady load at 20 payments a second, Kafka paused 40
seconds in for 60 seconds:

| | |
|---|---|
| Payment requests | 10,801, **2 failed** (0.019%): payments kept being accepted |
| Events waiting in the outbox at the peak | 2,705 |
| Backlog Prometheus recorded | 2,159 (sampled every 15 seconds, so below the true peak) |
| Outbox empty again | about 90 seconds after the broker returned, with load still arriving |
| Events written during the run | 7,178, **every one published** |
| Handled by notification-service | **every one, and none twice** |
| Books afterwards | balanced: 24,676 entries |

### The 60 second stall did not happen

The question raised before the run was whether `send()` would block for `max.block.ms`, 60 seconds, on
each attempt while the broker hung. It did not. The relay's failed attempts were **exactly 10.0 seconds
apart**, which is the publisher's own acknowledgement timeout: the producer already held the cluster's
metadata when the broker froze, so `send()` returned a future at once and the future timed out. The
likely cause is inferred, not measured. `max.block.ms` is left alone.

## Consequences

- **A hanging broker is tried one payment at a time, ten seconds each.** In the 60 seconds Kafka was
  paused the relay made 4 attempts, holding its claimed rows throughout. Nothing was lost and it caught
  up within about 90 seconds of the broker returning, but that recovery is serial: a longer outage, a
  larger backlog, or more payments behind the first failure stretch it out. Ending a pass at the first
  broker-level failure, rather than trying the next payment in the batch, is the change that would help,
  and it is left as a follow-up rather than made without a measurement that shows it matters.
- **The recorded peak is below the real one.** The gauges refresh every 15 seconds and Prometheus
  scrapes on its own schedule. For an alert on something that lasts five minutes that is irrelevant; it
  matters only to somebody reading the graph as an exact count.
- **The outbox table is append-only, and the test suite now proves the gauges without breaking that.**

## Alternatives

- **Alert on the count.** Fires on every busy minute, or never, depending on where the line is drawn.
- **Measure in the relay on each pass.** Couples a metric to the thing that fails: a relay stuck inside
  a pass would stop reporting exactly when the report matters.
- **Stop the broker container instead of pausing it.** A stopped broker refuses connections at once,
  which is the kind outage. It would not have tested the hanging case at all.
