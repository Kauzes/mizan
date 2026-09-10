# ADR 0033: Risk is a guard, not an invariant, so the platform fails open

- Status: accepted
- Date: 2026-09-10
- Jira: MIZ-58

## Context

This story puts a synchronous dependency in front of an authorization — the most consequential
change to the payment flow since it was written. A payment cannot be scored after it has been
authorized, because the point of scoring is to *not* authorize it, so the call has to be on the
request path and everything that follows is about surviving that.

The question the epic asks to be answered deliberately is: when risk cannot be asked, does the
payment go through or not?

## Decision

**Fail open, and record that it happened.**

The reasoning, which is the whole point of writing this down:

- **Risk is a judgement, not a correctness invariant.** The ledger balancing is an invariant:
  violate it and the books are wrong forever. "This payment looks like fraud" is an opinion, and
  a platform that treats an opinion as an invariant has confused the two.
- **The failure modes are not symmetrical.** Fraud let through during an outage is bounded by
  the length of the outage, visible afterwards, and recoverable — there are refunds and
  chargebacks, and this platform has both. Refusing every payment for every merchant is
  unbounded revenue loss for people who did nothing wrong, and is itself the incident.
- **The acquirer still checks.** This platform's scorer is not the only thing between a stolen
  card and a successful payment, and reasoning as though it were overstates its importance.
- **Every unscored payment is recorded as such**, with `UNAVAILABLE` as a real verdict rather
  than a null. A merchant asking why a payment went through unchecked, and an analyst reviewing
  a day of them afterwards, both need this to be a fact rather than an absence.

**A block never reaches the acquirer.** Nobody is contacted and no money is reserved, which is
the entire reason for scoring before rather than after. The refusal has no acquirer reference,
because there is nothing to reference.

**A review holds the payment and charges nobody.** `HELD_FOR_REVIEW` is a state of its own, not
a decline: the customer's money is untouched and the platform has not made its mind up, which
has to stay legible to a merchant as a different thing from having decided against them.

**The timeout is shorter than the acquirer's** — 500ms against 5s. A guard that takes as long as
the thing it guards has stopped being a guard and started being a second acquirer, with the
customer waiting for both.

**A circuit breaker, which is new to this platform.** Every other outbound call retries or
resolves, because everywhere else the answer matters enough to wait for. Here it does not:
without a breaker, risk being down costs *every* payment its full timeout, so a platform taking
ten payments a second accumulates latency faster than it sheds it and the guard becomes the
outage. With one, the first few failures cost a timeout each and everything after is refused
instantly until it is worth trying again.

Written by hand rather than taken from a library. It is sixty lines, and this platform has been
caught out repeatedly by Boot 4's module splits — a dependency whose Spring integration may or
may not exist yet, for behaviour this size, is the more expensive of the two.

**A held payment that nobody rules on expires, refused.** Not authorized. Letting a hold resolve
to "take the money" when nobody looked makes the safe-looking answer the one that happens by
default, which turns a review queue into a delay before approving everything. Expiry says so
plainly, including that nothing was charged.

**A released payment is not scored again.** An analyst releasing a held payment has overruled
the scorer; asking it a second time would let it overrule them back, forever.

**What risk decided is kept on the payment and returned by the API.** "Why was this held" is
asked long after the scorer's view of the world has moved on, and a scorer is a function of what
was known at the time — which is exactly what nobody can reconstruct later. A verdict recorded
only in a column is one a merchant has to open a support ticket to find out about.

## Consequences

**During a risk outage, fraud gets through.** That is the decision, stated plainly rather than
buried. What makes it survivable is that those payments are identifiable afterwards: every one
is marked `UNAVAILABLE` with the reason, and MIZ-59's queue is where a day of them can be
reviewed.

**A merchant cannot choose to fail closed.** For a high-risk merchant, an outage is exactly when
an attacker would strike, and the platform's answer is currently the same for everybody. A
per-merchant override is the obvious extension and is deliberately not in this story: it is a
policy decision with its own failure modes, and adding it as a side effect of the plumbing would
be the wrong way to arrive at it.

**`PaymentStatus` gained its first new state since MIZ-44**, and four tests asserted the old
shape of `CREATED.next()`. They were updated rather than worked around, which is the state
machine doing its job: adding a state somewhere payments can go is supposed to be visible.

**The suite ran out of database connections.** One JVM now runs several services at once, and
the arithmetic caught up: `sorry, too many clients already`, which reads like a leak. Fixed by
raising the container's limit and shrinking the pools the in-JVM peers use, which is the more
principled half — those contexts need a handful of connections, not ten each.

**A test race got worse.** `UnknownOutcomeTest` ran its sweep with no settle time, which was
survivable until MIZ-53 gave the sweep something to write on every pass and this story made
authorizing slower. It now leaves a payment alone for a moment first, which is what it does in
production anyway.

## Alternatives considered

**Fail closed.** Defensible, and it is the right answer for a platform where a single fraudulent
payment is catastrophic and unrecoverable — a lending platform, say. It is the wrong answer
here, where every merchant on the platform stops trading because one internal service is
unavailable, and where the harm it prevents is reversible.

**Queue the payment and score it later.** Neither open nor closed: hold everything during an
outage and decide afterwards. It sounds like the careful answer and it means every customer
waiting on a screen for an outage they cannot see, which is failing closed with extra steps and
a worse experience.

**Scoring asynchronously, after authorizing.** No timeout, no breaker, nothing on the request
path — and the money has already moved, so the only remaining action is a refund. Rejected in
ADR 0031 for the same reason.

**A library circuit breaker.** The standard answer, and it is the standard answer because
breakers are usually wanted with metrics, bulkheads, rate limiters and a configuration language.
This needs one breaker. Revisit when the second one is wanted.

**Making the breaker's state visible through an endpoint.** Worth having, and it belongs with the
observability epic rather than bolted onto this one; the state is on the client and the logs say
when it opens and closes.
