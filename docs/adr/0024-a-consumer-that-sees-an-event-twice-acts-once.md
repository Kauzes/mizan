# ADR 0024: A consumer that sees an event twice acts once

- Status: accepted
- Date: 2026-09-03
- Jira: MIZ-49

## Context

MIZ-48 publishes at least once and says so plainly: the relay publishes and then marks the
row, so anything that dies in between publishes again. A consumer group rebalancing mid-batch
produces the same thing. **Redelivery is this platform working normally, not failing.**

A handler not built for that sends a customer a second receipt on an ordinary restart, or
writes a number into the books twice. So every consumer has to be able to see an event twice
and act once, and that has to be easy enough to do that nobody writes a handler without it.

## Decision

**A handler records that it has handled an event, in the same transaction as the handling.**
One or the other happening alone is the failure. A handler that did its work and then failed
to record it is exactly the problem the outbox exists to prevent, from the other end.

**This is not the idempotency records from MIZ-41, and cannot share an implementation with
them.** They look alike and their transactional requirements are opposite:

| | API idempotency (MIZ-41) | Inbox (here) |
|---|---|---|
| Committed | **before** the handler runs, on its own | **with** the handler, in one transaction |
| Why | so a concurrent request sees the claim and waits for the first one's answer | so work and the record of it cannot come apart |
| Stores a response | yes — an HTTP caller is waiting to be given the same one again | no — nobody is waiting |
| Fingerprint of the request | yes — a caller can honestly reuse a key by mistake | no — a producer cannot reuse an event id |

Merging them would mean one of the two being wrong about when it commits. That is the answer
to the question MIZ-47 raised about three mechanisms that rhyme: the third is genuinely a
different thing, and the ledger's external reference (MIZ-36) is a third again, because there
idempotency is a property of the entity rather than a record beside it.

**Claimed by inserting, not by asking.** A check answers for the moment before the insert, and
two deliveries racing would both be told they were first. The unique constraint decides, and
decides once.

**`on conflict do nothing` rather than catching a duplicate key.** The insert is inside the
transaction that does the work, and a constraint violation inside a Postgres transaction leaves
that transaction unusable — so catching the exception would only move the failure to the
commit. This asks the database not to raise, and reads the answer from the row count. A
duplicate another transaction has inserted and not committed blocks until that transaction
ends, which is the behaviour worth having: two deliveries take turns and the second finds the
first's row.

**Keyed on handler and event, not on event alone.** Two handlers in one service may both care
about the same event and each must see it. "Already handled" is a question about a handler.

**The handler name is a constant, not a class name.** Renaming the class would otherwise make
every event it has already handled look unhandled, and it would redo all of them.

**An event a handler has nothing to say about is still recorded as seen.** It costs a row and
saves reconsidering the same event on every redelivery for as long as the topic keeps it.

**The uniqueness is written twice.** The inbox refuses to handle an event twice, and a unique
index on the notification's `caused_by` refuses to store two notifications from one event. The
same doubling as the ledger and the payment service: the invariant holds against a bug in the
layer above it.

**Offsets are committed per record after handling, not on a timer.** A timer would commit an
offset for a message still being handled, and a crash would then lose it — which is the one
thing an at-least-once consumer must not do.

**`auto-offset-reset: earliest`.** A consumer joining a topic that already has events should
see them. The alternative is that everything that happened before this service first started is
silently invisible to it forever.

## Consequences

**`handled_event` grows with every event this service sees, and nothing trims it.** A row can
only be removed once the topic can no longer redeliver the event it names, which is a retention
question rather than a code one. The index for that query exists; the decision does not.

**A handler that throws leaves no record and the event is redelivered.** Right for a transient
failure and a trap for a permanent one: the event is retried forever and, because ordering
within a payment is preserved, everything behind it waits. MIZ-50 is what moves it aside, and
this is why that story cannot be skipped.

**Two tests, and neither replaces the other.** One publishes messages this suite writes, so a
delivery can be repeated exactly and a failure provoked — but they are the right shape only for
as long as somebody keeps them so. The other starts the real payment service, the real ledger
and the real acquirer, takes a payment through, and waits for a notification. Nothing in it
knows what a payment event looks like, so the day the shape changes, it fails and the fixtures
do not.

**Notification-service gained a read endpoint**, slightly beyond this story. Without it the
only way to check the consumer end to end was for the smoke script to read another service's
database, which is the thing this platform says it does not do. Reading what a merchant will be
told is real product surface, and Epic 8 will deliver from this table rather than from the
topic.

**Three test configuration files moved to `common-test`.** More than one service now starts a
peer in its own JVM, and a copy per service is a copy that drifts.

## Alternatives considered

**Making handlers naturally idempotent instead, with no record.** Ideal where it is possible —
an upsert keyed on the payment would need nothing here. It is not possible in general: sending
a webhook, charging a card, or appending to a journal has no natural key to collapse on, and
"write your handler carefully" is exactly the instruction that is followed until it is not.

**Exactly-once delivery.** Discussed and rejected in ADR 0023. It would not remove the need for
this, because a consumer that writes to its own database still has to make delivery and its own
commit one act.

**Deduplicating in memory.** Free and forgets everything on restart, which is precisely when
redelivery happens.

**Recording only events the handler acted on.** Fewer rows, and every redelivery of an ignored
event is reconsidered from scratch. The row is cheaper than the reasoning.
