# ADR 0016: A payment is a state machine, written down before anything moves it

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-40

## Context

A payment happens over time and through other people's systems. It is created, sent to an
acquirer, approved or refused, and then taken or released — and each of those steps is a
separate request that can arrive twice, out of order, or not at all.

The failure mode is not one big mistake. It is the fourth endpoint quietly inventing a fifth
state, or two endpoints disagreeing about whether a declined payment can be captured, and
nobody noticing until a customer is charged for something that was refused.

## Decision

**The states and the transitions between them are written down in one place**, before the
stories that perform them exist. Most of these transitions are made by MIZ-43 through MIZ-45;
they will find the rules already there rather than invent them one endpoint at a time.

**A payment only ever moves forward.** There is no path back to the beginning, and the three
ends are ends. Money that moved is corrected by a refund, which is Epic 7, and not by a
payment quietly becoming voided.

**Every change goes through one method that knows how to refuse.** A payment cannot be moved
by setting a field, which is what keeps the machine from being advisory.

**A refusal names both states.** "That transition is not allowed" tells the reader nothing
they can act on; "a payment that is DECLINED cannot become CAPTURED, that is where this
payment ends" usually explains the whole misunderstanding without a second question.

**The response says where a payment may go next.** A client that has to hard code the machine
to know whether to offer a capture button is a client that will disagree with the platform
about it eventually.

**Every step is recorded, and the record is append only**, enforced by the database as the
journal's is in ADR 0012. Current state answers "what is true now"; the history answers "how
did this happen", which is the question asked when something has gone wrong, and it cannot be
reconstructed from the former.

**An intent contacts nobody and moves nothing.** It exists so that an authorization has
something to be attached to, and so that a caller whose response was lost can find the payment
again by their own reference rather than creating a second one.

**The merchant's reference is unique within the merchant.** It is what their reconciliation
joins on, and it is the thing they will use to ask "did that one go through".

## Consequences

Adding a state means editing the machine, which is the point: the edit is visible in review
rather than distributed across the handlers that would otherwise each learn about it
separately. MIZ-44 adds one for an outcome that is not yet known, and that will be a change to
this file's enum and its tests, in one place.

The history table grows without bound and nothing prunes it. For payments that is correct —
the history of a payment is part of the record — but a service with a very high payment volume
would eventually want it partitioned by time.

Because a payment can only move forward, an operational mistake cannot be corrected by moving
it back. That is deliberate and it means operational fixes have to be expressed as the thing
that really happened: a refund, or a new payment.

Nothing here is idempotent yet beyond the merchant's reference being unique. MIZ-41 adds the
`Idempotency-Key` mechanism that the transitions need, and it needs to exist before anything
calls an acquirer.

## Alternatives considered

**Statuses as strings with the rules in the service methods.** What most of these systems have,
and the reason they eventually have two answers to whether a declined payment can be captured.

**A general purpose state machine library.** Buys transition tables, guards and listeners, and
costs a vocabulary everybody has to learn to read the payment code. Five states and six
transitions do not need a framework; fifty would.

**Deriving history from an event log rather than storing transitions.** Where this ends up if
the platform adopts event sourcing, and a much larger decision than this story. The transition
table is the small honest version: it records what happened, in order, and cannot be edited.

**Letting an intent be created without a merchant reference.** Simpler for the caller, and it
removes the only handle they have for finding a payment again after a lost response. The
reference costs a caller nothing and is the first thing anybody reconciling wants.
