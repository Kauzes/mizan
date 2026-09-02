# ADR 0022: An event is written in the transaction that caused it

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-47

## Context

The platform is about to start telling other services what happened. The first question is
not which broker or which topic. It is whether an event can ever disagree with the state that
caused it.

In the obvious design it can, and silently. Change the payment and then publish: if the
publish fails, the money moved and nobody was told. Publish and then change: if the change
fails, the platform announced something that did not happen. Neither failure produces an
error anybody sees, and both are found later by a person reconciling by hand.

The reason is simple and does not go away: a broker cannot join a database transaction.

## Decision

**The event is a row, written through the same connection, in the same transaction as the
change.** Capturing a payment and recording `payment.captured` happen in one method inside one
transaction, so they commit together or roll back together. There is no ordering of failures
that separates them, because there is no second system involved at the moment it matters.

**Nothing is published in this story.** Not a publisher, not a relay, not a topic. Writing a
row is the only step that can be made atomic with the change, so it is the only step taken
here. MIZ-48 drains the table.

**Recording outside a transaction is refused, loudly.** A row written on its own commits on
its own, which is precisely the failure the table exists to prevent, so `Outbox.record` checks
that a transaction is actually active and throws if it is not. The alternative is a mechanism
that appears to work and quietly gives up its only property.

**The envelope is separate from the payload.** Everything needed to route, deduplicate, order
and trace an event lives in fields whose meaning does not change between one event type and
the next — id, type, version, aggregate type and id, merchant, when, correlation id. That is
what lets a relay, a dead letter viewer and an idempotent consumer each be written once rather
than per type.

**A payload is a record written for consumers, never an entity handed to a serialiser.** An
entity serialised is a published contract that changes whenever somebody renames a column, and
the break appears in a different repository weeks later. A test asserts the exact field set of
an authorized payload, so widening it is a deliberate act.

**The aggregate id is the ordering key.** Events about one payment stay in order relative to
each other. That is the only ordering a partitioned log can honestly offer, and deciding it
here rather than in MIZ-48 keeps the promise a property of the data rather than of the
transport.

**The event types a service publishes are an enum, and each service owns its own.** A
published event is a promise to whoever consumes it, and promises should not appear because
somebody typed a string. A platform-wide catalogue would put the definition of a payment event
somewhere other than the payment service and recompile everything when any service adds one.

**Creating a payment announces nothing**, and neither does a payment whose outcome is unknown.
An intent contacts nobody and moves no money; announcing it is this service narrating its own
database. Not knowing is a question rather than an outcome, and MIZ-44 answers it within
seconds — the answer is what gets an event.

**The table is append-only except for `published_at`**, enforced by a trigger, so an event
cannot be rewritten after the fact and a published event cannot be quietly unpublished to be
sent again. Deleting is deliberately left alone: a published event older than any retention
anybody needs is rubbish rather than evidence, and a table that only grows is its own outage.

## Consequences

**Every event will be published at least once, and this design is the reason.** The relay
publishes and then marks the row, and a crash in between means publishing again. That window
can be made small and cannot be removed. Saying so here is what makes MIZ-49's idempotent
consumers obviously necessary rather than a nicety.

**An event is only as prompt as whatever drains the table.** Nothing does yet, so at the end
of this story the platform records events and tells nobody. That is the correct half to build
first, and it looks like no progress at all, which is worth noticing about this kind of work.

**A third mechanism in this codebase now means "do this exactly once".** The journal's
external reference (MIZ-36), the API's idempotency records (MIZ-41), and now an event id a
consumer will deduplicate on. They are not the same thing — one keys on what a caller called
the request, one on a key the caller chose, one on an id the producer generated — but they
rhyme, and MIZ-49 is where it is worth asking whether the third should reuse the second.

**The auto-configuration was written and not registered**, and everything compiled. It failed
loudly only because a bean depended on the missing one; a service that merely wrote through it
would have started perfectly and recorded nothing. That is the same shape as MIZ-41, where
idempotency was inactive on every write while the suite stayed green. A test in `common-web`
now checks the imports file against the classes, so the next one cannot be silent.

## Alternatives considered

**Publishing directly from the service, in the transaction.** Fewer moving parts and it does
not work: the broker acknowledges outside the transaction, so a rollback after a successful
publish announces something that did not happen.

**Publishing after the transaction commits**, from a listener. Much closer to correct, and it
loses events whenever the process dies between the commit and the publish — which is exactly
when it matters, because the state has already changed.

**Change data capture off the write-ahead log** — Debezium and its relatives. No outbox
writing code at all, real exactly-once semantics into the log, and it makes every column name
in every table a published contract, needs a connector deployed and operated next to the
database, and puts the event schema outside the service that owns the data. Worth it at a
scale this platform is not at; the cost is a piece of infrastructure a reader has to
understand before they can understand a capture.

**A shared `outbox_event` table in one database.** One relay instead of one per service, and
it puts every service in the same database, which is the thing ADR 0004 spent its length
refusing.

**JPA entity rather than plain SQL.** Nicer to write, and an entity in a shared module must be
scanned into each service's persistence unit — configuration that has to be right in six
places instead of none. The same reasoning as `IdempotencyStore`.
