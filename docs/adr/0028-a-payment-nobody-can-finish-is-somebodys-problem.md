# ADR 0028: A payment nobody can finish is somebody's problem, not the platform's secret

- Status: accepted
- Date: 2026-09-06
- Jira: MIZ-53

## Context

By this point there are two ways for money to end up somewhere no amount of retrying will move
it: an authorization the acquirer has no record of (MIZ-44) and a refund whose saga gave up
(MIZ-52). A third — a capture the books refused because an account was never opened (MIZ-45) —
is recoverable by the merchant and is not stuck in the same sense.

Each was handled correctly by the story that created it. Each was invisible unless you knew
which different place to look in, and two of those stories ended by pointing at this one.

MIZ-44's sweep also asked the acquirer about an unresolved payment on *every pass, forever*.
That is right while there is any chance of an answer and wrong once there plainly is not.

## Decision

**One place that answers "what is stuck, and why", across kinds.** Deliberately built last:
each earlier story could have grown its own operator view and they would have been three views
of the same question. The shape is only obvious once all the kinds exist.

**Giving up is not an answer, and is not a state change.** A payment the platform has stopped
trying to resolve is *exactly as unknown as it was*. What changed is that the platform has
stopped working on it alone, which is a fact about the platform rather than about the money — so
it is a separate field, not a new `PaymentStatus`. A status that meant "we gave up" would be a
status that lies about where the money is.

**Bounded attempts, then a person.** An acquirer that has never heard of a request will not have
heard of it tomorrow either. After enough passes the payment leaves the sweep, which is the
difference between a platform that is patient and one that is stuck.

**What we believe and what the acquirer believes, side by side, asked live.** That comparison is
the first thing anybody does, so it is done here rather than left as an exercise. Asked live
rather than remembered, because the entire reason somebody is looking at this list is that the
platform's own record is not to be trusted. A failure to ask is shown as such: "the acquirer
cannot be reached" is a different problem from "the acquirer has no record", and an operator
should not have to guess which they have.

**Two verbs, and no more.** `RETRY` puts it back in front of the sweep; `CLOSED` records that a
person has dealt with it. Nothing here moves money on an operator's say-so. An endpoint that
did would be a way to write the books by hand, which is exactly what a double-entry ledger
exists to make impossible — an operator who genuinely needs that posts a correcting entry
through the ledger, where it is an entry like any other and just as visible.

**A decision records who, when, why, and what it changed.** *What it changed*, not what was
intended: "retried it" and "asked for it to be retried" are different claims and only one is
checkable afterwards. The table is append-only by trigger, because evidence the next decision
can overwrite is not evidence — the same reasoning as the journal.

**A decision with no owner or no reason is refused.** An audit trail whose entries nobody owns
and nobody explained is a log, not an audit trail.

**Closing changes nothing about the money.** The payment stays `AUTHORIZATION_UNKNOWN`; the
abandoned refund stays `ABANDONED` and keeps its reservation. Saying "a person looked and
decided" is more honest than the platform pretending it worked something out.

**The same shape as MIZ-50's dead letters** — what is outstanding, why, and an action to take —
because it is the same question about a different subject, and two operator views answering it
differently is one view too many.

**The smoke check asserts nothing is stuck**, so a regression that starts stranding payments
fails a pull request rather than being noticed next quarter.

## Consequences

**`decidedBy` is taken from the request, not proven.** Actuator endpoints sit outside the
platform's authorization machinery, which only guards `/api/`, so the "who" in this audit trail
is worth exactly as much as the honesty of whoever typed it. It is written down here, in the
migration, and in the endpoint rather than left to be discovered. The honest fix is operator
identity at the edge, which is a story rather than a line of code.

**An abandoned refund holds its reservation even after being closed.** That is the MIZ-52
decision unchanged: the money may have gone back, and releasing it would allow a double refund.
Closing says a person has looked, not that the money is accounted for.

**Only one kind of payment ever becomes stuck today**, so the acquirer comparison always reads
"unknown versus no record" for payments. It earns its place on the refund side, where the
acquirer knows the payment perfectly well — and the test that shows it is the refund one for
exactly that reason. A test asserting otherwise for payments was written and removed, because
it was asserting something the design cannot produce.

**The list is unpaginated and unfiltered.** Right while "what needs a person" is nearly always
empty, which is the intended state. A platform where it is not is a platform with a different
problem, and pagination would be the least of it.

**A third sweep-shaped thing now has a bounded-attempts-then-give-up rule**, after the outbox
relay and the refund resolver. The rule is the same and the code is not shared, for the reasons
in ADR 0027.

## Alternatives considered

**A `NEEDS_ATTENTION` payment status.** Reads well and destroys the meaning of the status field:
the payment's state is what is true about the money, and "we stopped trying" is not that.

**Letting an operator set a payment's outcome directly** — mark it declined, mark it captured.
The obviously useful feature, and it is a hand-operated lever on money with a double-entry
ledger sitting right there. If the acquirer confirms by telephone that nothing was reserved,
the honest record is a closed decision saying so, not a platform pretending it found out.

**A separate operator service.** Where this ends up if it grows, and today it would be a service
whose only job is to read one other service's tables — which is the thing this platform says it
never does.

**Merchant-facing rather than operator-facing.** A merchant with a stuck payment does need to
know. What they need is a status and a sentence, not a retry button on somebody else's saga;
that belongs in the console (Epic 9) reading the payment, not here.

**Alerting on it.** The log is at `error` with a `NEEDS A PERSON` prefix and the endpoint counts
them, which is what alerting would be built on. Wiring an actual alert is Epic 11's job and
doing it here would mean choosing a monitoring stack in a story about payments.
