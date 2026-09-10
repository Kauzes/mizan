# ADR 0031: A score that cannot say why is a decision nobody can fix

- Status: accepted
- Date: 2026-09-06
- Jira: MIZ-56

## Context

Risk scoring decides whether a payment is taken. It is the first thing on this platform that
will refuse a customer for a reason that is a judgement rather than a fact — the acquirer
declining is a fact, and "this looks wrong to us" is not.

That difference is the whole design constraint. A merchant will ask why, a customer will be
angry, and an analyst will have to decide whether the platform was right. All three need
something to point at.

## Decision

**Scoring returns the reasons, not a number.** A score of 68 tells a merchant nothing they can
act on and an analyst nothing they can check. "The amount is nine times this merchant's usual,
and this card has never been seen here" is a sentence somebody can agree or disagree with — and
a decision nobody can argue with is a decision nobody can fix.

Each reason carries what it contributed, so the arithmetic is visible rather than implied.

**Three verdicts, not two.** A scorer that can only approve or block has to be certain about
everything, so it is tuned either to let fraud through or to refuse honest customers — and
whichever it does, somebody chose it by picking a threshold without meaning to. `REVIEW` is the
honest answer for a payment that is unusual and not obviously wrong: hold it, charge nobody, let
a person decide. It costs a delay, which is a real cost and a smaller one than either mistake it
replaces.

**Signals add up rather than being tested one at a time.** A payment that is slightly odd in
four ways is more interesting than one that is slightly odd in one, and no single rule can
express that. It is also what lets a cheap signal like "exactly round" exist at all: useless
alone, useful in combination.

**The rules are a closed enum with their weights on them.** Somebody reading it knows everything
the scorer can object to. Somebody reading a hundred-line method knows what they had the
patience for. Having the weights in one place is also what makes them tunable by something other
than a person, which is MIZ-59.

**One rule lowers the score.** Without it, a scorer grows more suspicious of a customer the
longer they stay — a platform that refuses its own best customers on a busy day. A card that has
paid this merchant before, without trouble, is evidence and is treated as such.

Two guards on that, both of which are the interesting part:

- **The total is floored at zero.** Otherwise a trusted card banks credit and can absorb a
  genuinely alarming amount, which is exactly what somebody who has stolen a trusted card would
  want.
- **A card that was also recently declined gets no credit.** Known here *and* just refused is a
  stolen card being used where it had worked before, which is worse than either fact alone and
  must not be able to cancel itself out.

**Scoring is a pure function of what it is given.** No clock, no database, no randomness — even
the timestamp is an input. The same request scores the same way twice, which is what makes a
disagreement about a decision resolvable: somebody who was not there can reproduce it. It also
means any situation can be constructed in a test directly rather than arranged, and the
knowledge-gathering can be replaced wholesale in MIZ-57 without touching a single rule.

**A merchant with no history is not treated as suspicious.** Cold start is the common case on a
new platform, and a scorer that blocks the first payment every merchant ever takes is one nobody
switches on. Rules that need a baseline stay silent without one; that is a fact about what is
known, not a suspicion.

**Each merchant's own line decides where approve becomes review.** One line for everybody is one
line that is wrong for nearly everybody: a marketplace taking a thousand small payments an hour
and a car dealer taking three a week have different ideas of alarming. A merchant with no row of
their own uses the platform's defaults, which is not a row pretending to be a decision.

**No card number, ever.** The caller supplies a fingerprint, so the same card is recognisable
across payments without this service holding one. Nothing about the card comes back in the
response either.

**Speed is asserted, not claimed.** This is going in front of an authorization in MIZ-58, and a
guard that costs as much as the thing it guards has stopped being a guard. A test measures it
rather than a comment asserting it.

## Consequences

**Nothing calls this yet.** Putting a synchronous dependency in front of an authorization is a
decision with its own consequences — a timeout, a circuit breaker, and a fail-open-or-closed
choice — and it deserves its own story rather than arriving as a side effect of this one. That
is MIZ-58.

**Every rule that needs a baseline is currently silent**, because nothing supplies one.
Deliberate: the shape of `WhatWeKnow` is the contract MIZ-57 fills in, and the rules will not
change when it does.

**The weights are guesses.** `UNUSUAL_MULTIPLE` of three, `RAPID_ATTEMPTS` of three in a window
— all chosen by argument rather than measurement. They are constants with comments explaining
the reasoning rather than magic numbers, which is the best that can be done before there is
traffic to measure. MIZ-59 is where analyst rulings start moving them.

**One weight moved while writing the tests.** `RAPID_ATTEMPTS` was 35, which meant card testing
with a recent decline scored 60 and went to review. It blocks now, because a review queue that
fills with unambiguous card testing buries its own value for the genuinely ambiguous cases, and
nobody should have to rule on the sixth attempt from a card declined five minutes ago.

**The endpoint is public at the service and behind a token at the gateway.** It holds no data and
changes nothing — the request carries everything it is scored against — so there is nothing to
authorize access to, but a merchant should not be able to ask what trips the scorer, because
knowing exactly what trips it is most useful to somebody trying not to. MIZ-58 puts it behind the
service credential when the payment flow actually calls it.

## Alternatives considered

**A single number and a threshold.** Simpler, and it produces exactly the support ticket nobody
can answer: "why was my payment refused" met with "it scored 68".

**Rules as configuration rather than code.** Tempting, and it turns every rule into a small
language nobody has designed, evaluated by an interpreter nobody has tested. The enum is
readable, typed, and changed by a pull request somebody reviews.

**Machine learning.** The right answer at a scale with labelled data, and this platform has
neither. It also produces the thing this ADR is named after: a model can rank, and explaining a
particular decision is a research problem. The rules here are deliberately explainable, and
MIZ-59 adds the feedback that a model would have needed labels for anyway.

**Scoring asynchronously, after authorizing.** No timeout, no breaker, no risk of slowing a
payment down — and the money has already moved, so the only remaining action is a refund. The
point of scoring is to not authorize it.

**Letting the scorer look things up itself.** One fewer parameter and it would make every
decision depend on a database at the moment it was taken, so no decision could be reproduced
afterwards and no test could construct a situation without arranging one.
