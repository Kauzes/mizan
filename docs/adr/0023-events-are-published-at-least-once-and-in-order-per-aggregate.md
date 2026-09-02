# ADR 0023: Events are published at least once, and in order per aggregate

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-48

## Context

MIZ-47 wrote events into a table in the transaction that caused them, and told nobody. This
is the half that publishes them, and the questions it has to answer are: how often can an
event arrive, in what order, and what happens when it will not send.

## Decision

**At least once, and this is stated rather than worked around.** The relay publishes and then
marks the row. A process that dies in between publishes again. That window can be made small
and cannot be closed: marking first would lose events instead, which is worse, and there is no
way to make a database commit and a broker acknowledgement one atomic act. Every consumer must
therefore be built for repeats, which is MIZ-49 and is now obviously necessary rather than a
nicety.

**Ordering is per aggregate, not global.** Every event about one payment carries that payment
as its key, so they land in one partition in the order they were written. Across payments there
is no order and none is claimed — a partitioned log cannot offer one without becoming a single
partition, which is a throughput ceiling of one consumer forever.

**A total order comes from a sequence, not a timestamp.** Two events a microsecond apart are
ordered by their timestamps; two in the same microsecond are ordered by nothing. The relay
publishes by `sequence`, assigned at insert.

**More than one relay may run.** Rows are claimed `for update skip locked`, so two instances
take different work rather than blocking or double-publishing.

**That alone does not preserve order, so there is a second check.** If one instance holds a
payment's earlier event while another picks up its later one, the later could be published
first and a consumer would see a capture before its authorization. So before publishing
anything for an aggregate, the relay asks whether anything older for that aggregate is still
unpublished; if so it leaves that aggregate for the next pass. A test holds an earlier row on a
second connection and asserts the later one does not overtake it.

**A failure blocks its own aggregate and nothing else.** An event that will not publish must
hold back that payment's later events — they cannot go out in order until it does. It holds
back no other payment: the check is per aggregate, and the retry delay takes the failing row
out of the running until its time comes. Both halves are tested.

**Retries back off, doubling to a cap, with jitter.** A broker that is down for an hour should
be retried through that hour rather than every second of it, and a hundred events deferred by
one outage should not all return at the same instant.

**The failure is written on the row.** Attempts and the last error, so a row stuck at the front
of a payment's queue can be explained without finding a log that has rotated.

**One topic per aggregate type, named in one place**, as `mizan.<aggregate>.events`. Not one
per event type: spread across `payment.authorized` and `payment.captured` a payment's events
would be in different partitions of different topics and their order would mean nothing. No
version in the topic name — the payload's version is on the envelope, where a consumer can read
it and say "I do not understand version 3" rather than silently receiving nothing.

**The topic is declared, not conjured.** Automatic creation is off on the broker, so a typo in
a producer is an error rather than a new topic with default partitioning nobody chose. Three
partitions, because ordering is guaranteed by the key rather than by there being one, and
partitions cannot be reduced later without breaking the ordering that already went out.

**The publisher is behind an interface and the relay knows nothing about Kafka.** That is what
makes the interesting behaviour testable: a publisher that can be told to refuse is the only
way to check what happens to the events behind one that will not send.

**Publishing is synchronous.** The relay marks a row published when `publish` returns, so a
method that returned as soon as the event was queued would record as delivered things still
sitting in a buffer in this process, to be lost on a restart with no trace.

## Consequences

**Rows are locked while events are published**, which is network work inside a database
transaction. Nothing is blocked by it — other instances skip locked rows — but it is why the
batch is small: a batch that takes a minute is a batch whose whole transaction is lost if
anything fails at the end of it.

**A consumer can see the same event twice, and must not act twice.** Nothing downstream may be
written on the assumption of exactly once.

**An event that never publishes stops that payment's later events indefinitely.** That is
correct — order is the promise — and it means a permanently poisoned event is a permanently
stalled payment stream. Nothing yet notices that or moves it aside; MIZ-50 is the dead letter
story, and this ADR is why it cannot be skipped.

**The relay is per service and per instance, with no leader.** Simple and correct, and it means
every instance polls the table on its own timer, which is wasted work at low volume and fine at
this scale.

**Boot 4 keeps Kafka's auto-configuration in `spring-boot-kafka`**, separate from
`spring-kafka`. Depending only on the latter gives the library and no beans, and the failure is
a missing bean that mentions nothing about Kafka. Third module split this platform has hit,
after Flyway and the HTTP clients.

## Alternatives considered

**Exactly once, with Kafka transactions.** Genuinely available between Kafka and Kafka. Between
Postgres and Kafka it needs a distributed transaction to be real, and what it actually buys in
practice is the same at-least-once delivery with more configuration and a worse failure mode
when the transaction coordinator is unhappy. Idempotent consumers are simpler and hold under
every failure.

**Marking the row published before sending.** Closes the duplicate window and opens a worse
one: an event lost with no record that it ever existed. Duplicates are a nuisance a consumer
can be built to absorb; a silently dropped `payment.captured` is money nobody was told about.

**A global order across all payments.** One partition, one consumer, forever, in exchange for
an ordering nothing in this platform has asked for. Consumers care what happened to *a* payment
in order.

**Skipping a failing event to keep the stream moving.** Keeps throughput up and delivers a
capture before its authorization, which is precisely the thing a consumer cannot recover from.
Blocking one payment is the smaller harm.

**Leader election so only one instance relays.** Removes the ordering problem outright, and
adds a coordination mechanism, a failover delay, and a new way for publishing to stop
completely. The two checks here are cheaper and have no single point of failure.
