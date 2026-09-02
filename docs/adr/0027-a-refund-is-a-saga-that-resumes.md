# ADR 0027: A refund is a saga that resumes, not a transaction that cannot

- Status: accepted
- Date: 2026-09-03
- Jira: MIZ-52

## Context

A refund is three steps across two other systems that do not share a transaction with this one:
reserve the amount, ask the acquirer for the money back, tell the ledger. A process can die
between any two of them, and all three resulting failures are silent:

- the acquirer gave the money back and the ledger never recorded it, so the books say the
  platform holds money it does not;
- the ledger recorded it and the refund was never marked, so a retry is safe but nothing
  retries;
- the acquirer was asked and did not answer, so nobody knows whether the money moved.

MIZ-51 wrote the refund row only once everything had worked. That was honest for a story with
no way to resume, and it means a crash during the acquirer call left the money possibly gone
and *nothing at all* saying so.

## Decision

**A saga with resumption, not a two-phase commit.** The acquirer is somebody else's system
reached over HTTP; it has no prepare phase and will never have one, so a distributed transaction
is not an option that was passed over — it is not available. What is available is making each
step recoverable, and that is what the rest of this decision is.

**Where the refund has got to is written down before each step, in a transaction of its own.**
A crash then leaves a record of what was attempted rather than nothing. This is the fourth time
this platform has met that ordering problem, after MIZ-33, MIZ-36 and MIZ-44, and the first time
it has cost real money to get wrong.

**Each state is a real answer to "where is the money right now"**, which is the only question
that matters when a process has died halfway:

| | what is true | reservation |
|---|---|---|
| `REQUESTED` | the acquirer has confirmed nothing; the money may or may not be gone | held |
| `RETURNED` | the money is back and the books do not say so | held |
| `SUCCEEDED` | the money is back and the books say so | held |
| `FAILED` | the acquirer refused; nothing moved | **released** |
| `ABANDONED` | nobody could finish it; a person has to look | held |

**"It said no" and "it said nothing" are different facts.** Only an outright refusal releases
the reservation. A silence keeps it, because the money may already be gone and handing the
merchant their headroom back would let them refund the same money twice. Conflating the two is
the single most expensive mistake available here, and the state machine is shaped to make it
impossible to make by accident.

**Resuming continues from the step reached; it never restarts.** The acquirer must not be asked
twice for money it has already returned. A test proves this by switching the acquirer *off*
before resuming a refund that had already reached `RETURNED`: if resumption restarted, that test
fails.

**Retrying and asking are the same request here.** The acquirer's refund is keyed on the
merchant's own reference and answers a repeat with what it already did, so "try again" and "what
did you do with this reference" are one call. MIZ-44 needed a separate lookup endpoint because
authorizing again would have reserved money twice; that argument does not apply to an operation
that is already keyed, and inventing a second endpoint to look symmetrical would have been
ceremony.

**One path, exercised by every refund.** The synchronous request and the sweep both go through
the same `finish` method, so the recovery path is the ordinary path rather than code that only
runs when something has gone wrong — which is code that is only tested when something has gone
wrong.

**A bounded number of attempts, then a person.** Retrying forever is how one broken refund
becomes a service doing nothing else. An abandoned refund keeps its reservation and stops being
swept.

## Consequences

**An abandoned refund holds its reservation forever until somebody acts.** Deliberate: the
alternative risks refunding twice. It means an ignored abandoned refund permanently reduces what
the merchant can refund on that payment, which is the intended pressure — and there is nowhere
yet for an operator to see it or act on it. That is MIZ-53, and this story is the second of
three now pointing at it.

**A refund can be `SUCCEEDED` while the `payment.refunded` event has not been published**, for as
long as the outbox takes. The event is written in the same transaction as the state, so it
cannot be lost, only late. That is the guarantee MIZ-47 chose and it holds here.

**The sweep is the third of its shape**, after MIZ-44's authorization resolver and MIZ-48's
outbox relay. I looked at sharing them and did not: they differ in table, state and action, and
what is genuinely common is only the backoff arithmetic — perhaps twenty lines duplicated in
three places, against an abstraction that would have to be parameterised by all three of those
things. Written down here so the next occurrence is a deliberate decision rather than a fourth
accident.

**Failure injection needed the clients to be replaceable**, so the ledger and acquirer clients
are now non-final with overridable methods. That is a small design cost paid for the ability to
test the states a crash leaves, which cannot be tested any other way.

## Alternatives considered

**A two-phase commit.** Not available: the acquirer is an HTTP API belonging to somebody else.
Even between the two services this platform owns, it would mean holding locks across a network
call, which is how a slow ledger becomes a stalled acquirer.

**Compensating the acquirer instead of resuming.** The textbook saga answer: if the ledger
refuses, un-refund at the acquirer. It is wrong here, because the ledger refusing is not a
reason to take money back off a customer who has already been given it — the correct response to
"the books do not know yet" is to tell the books, not to reverse the world.

**Driving the saga from events rather than a sweep.** More fashionable and it makes the recovery
path depend on the broker being healthy at the moment something is already failing. A table and
a timer depend only on the database this service already cannot work without.

**Releasing the reservation whenever a refund cannot be finished.** Kinder to the merchant, and
it is exactly how the same money gets refunded twice.

**Keeping the amount only on the refunds and summing.** Removes the reservation concept, and
means the limit cannot be enforced under a single row lock. Discussed in ADR 0026.
