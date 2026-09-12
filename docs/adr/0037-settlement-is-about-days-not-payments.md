# ADR 0037: Settlement is its own service, because it is about days rather than payments

- Status: accepted
- Date: 2026-09-12
- Jira: MIZ-69

## Context

Authorizing and capturing is not the same as being paid. Settlement is the difference: a day's
captures grouped per merchant, a fee taken out, an amount owed, a payout, and eventually a bank
statement to reconcile against.

Three places it could live, and the epic explicitly asks for the choice to be made and written
down.

- **payment-service** already knows about captures. But its subject is one payment and its
  lifecycle; settlement's subject is a day and a merchant. Putting both in one service means one
  database holding two aggregates with unrelated invariants, and a table named `settlement_batch`
  next to a table named `payment` invites exactly the join that makes them impossible to
  separate later.
- **ledger-service** is where the entries land. But the ledger's entire value is that it knows
  nothing about *why* money moved: it takes balanced entries from anyone entitled to post them
  and refuses everything else. Teaching it what a merchant fee is would give it an opinion, and
  the next opinion is a rule about which entries it may write itself.
- **Its own service.**

## Decision

**A `settlement-service`, with its own database, consuming `payment.captured`.**

- **Its subject is a different one.** A batch is a decision about a set of payments taken over a
  period. Nothing in it is a property of a payment, and nothing in a payment changes when a
  batch closes.
- **It grows into three more stories.** Statements, reconciliation runs, and differences a person
  has to rule on — all of which are about days and banks rather than about payments. Adding them
  to payment-service would double the size of the service that must never be down.
- **Everything it needs already crosses the wire.** `payment.captured` carries the amount, the
  currency, the acquirer reference and when the money moved. The inbox pattern from MIZ-49 makes
  the at-least-once delivery safe, and settlement never reads another service's database — the
  boundary every service here already keeps.
- **Its failure is a different failure.** Settlement being down means merchants are not paid
  today, which is serious and survivable. Payments being down means no money comes in at all.
  Sharing a process makes the second failure possible for the sake of the first.

**The cost, stated plainly:** another service, another database, another image, another consumer
group, another thing to run. That is the price of the boundary, and it is only worth paying
because the boundary is real.

## The day a capture belongs to

Decided from when the money moved, in one zone configured for the whole platform, and stored on
the row. Not from when this service heard about the event: a consumer that was down for an hour
would otherwise shift payments into a day they did not happen in, and a batch cannot express a
payment belonging to two days.

One zone rather than per merchant, for now. A merchant and an acquirer that disagree about when
Tuesday started cannot reconcile Tuesday, and the acquirer's day is the one that is not
negotiable. When a second acquirer with a different cut-off arrives, this becomes a property of
the acquirer rather than of the platform, and this paragraph is where to start.

## The fee has to add up twice

The requirement that shaped the code: the fee charged on a batch and the fees attributed to the
payments in it must be the same number, to the minor unit.

So the percentage is worked out **once**, on the batch total, and then allocated across the
payments by amount using the ledger's allocation rule. The parts add back to the whole by
construction. Charging 2.9% of each payment and summing them would not: every rounding loses a
fraction, nothing in the system would notice, and a merchant adding up their own statement would
get a different number from the one they were charged.

Half-up rounding, said out loud because it is a choice: rounding down means the platform quietly
gives away a fraction of every batch, rounding up means taking one.

All three figures — captured, fee, net — are stored, along with the rule as it was applied. A
figure derived at read time is a figure that moves when the rule changes, and a settlement a
merchant has already been shown must not move.

## Closing is repeatable

A batch claims its payments by writing its id onto them in the same transaction that creates it.
A second close finds nothing left to claim and answers with the batch that already exists; a
unique constraint on (merchant, day, currency) settles the race if two closes run at once. A
payment belongs to exactly one batch forever.

That is what makes the close safe to hand to a person during an incident, which is the only time
anybody runs one by hand.

## Consequences

- Refunds are not netted into a batch. Money going back has its own timing and its own movement
  in the books, and folding it into a settlement total is how a merchant loses the ability to see
  either. What that means for a payout is MIZ-70's problem.
- A batch per currency as well as per merchant. A total in two currencies is not a total.
- Three services now consume the payment events, each with its own dead letter topic. One of them
  failing says nothing about the others.
