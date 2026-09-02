# ADR 0018: The acquirer is driven by test cards, not by a switch

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-42

## Context

Everything interesting about a payment platform happens when the bank does something other
than approve. A decline that cannot be provoked is a path nobody has run, and a timeout that
only happens in production is a bug that is only ever debugged in production.

So the simulator's value is entirely in whether those cases can be made to happen on demand,
and in whether making them happen leaves the platform honest.

## Decision

**Behaviour is chosen by the card, not by a switch somebody flips.** The last four digits
decide: `0002` declines for insufficient funds, `0069` approves but withholds the answer,
anything unrecognised approves.

This is the decision that matters. A configuration flag or a test-only header would mean the
payment service has to know it might be talking to a simulator — that there is a mode in which
things pretend. Test cards keep that knowledge entirely on the acquirer's side: the platform
sends a card and gets an answer, in the test and in production, and there is no
simulation-shaped hole in it. It is also how real acquirers publish sandboxes, so a merchant
integrating against Mizan learns a habit that transfers.

**A slow card records the authorization and then withholds the answer.** In that order, always.
The whole point is the state where the money is reserved and the caller does not know it, which
is what MIZ-44 has to resolve; recording after the wait would simulate a different and much
easier problem.

**The same request id returns the first decision.** A real acquirer does this, and it is what
makes asking again safe for a caller who never heard the answer.

**There is a lookup, by the caller's own request id rather than the acquirer's reference.** A
caller whose answer was lost never learned the reference, so a lookup keyed on it would be
useless precisely when it is needed. This endpoint is the reason the simulator keeps anything
at all.

**It keeps only the last four digits of a card.** A simulator has no more business holding a
card number than a bank does, and a test asserts the response never contains one.

**State lives in memory.** This stands in for a system outside the platform, and giving it a
database would suggest the platform owns what it knows. A restart forgets every authorization,
which is the right amount of durability for something whose job is to be predictable while the
real thing is not.

**It is not under `/api/` and not routed through the gateway.** It is not part of the
platform's API, and a merchant should not be able to reach it or know it exists.

## Consequences

A merchant integrating against the local stack has to be told which cards do what, which is why
the catalogue is in the operation's description in the spec rather than in a comment.

Because the simulator forgets on restart, a timeout resolved after a restart finds no record of
the request — which the lookup reports as "no record", and which MIZ-44 must treat as a real
answer meaning nothing happened. That is the correct interpretation for a real acquirer too, so
the simplification does not soften the problem.

Nothing here simulates partial captures, currency conversion, or an acquirer that approves and
later reverses. Those are honest gaps and would each be a story.

The slow card blocks a request thread for the configured duration. That is fine for a simulator
and would not be for anything else; the duration is configuration so tests can make it short.

## Alternatives considered

**A configuration flag or an admin endpoint that puts the acquirer into decline mode.** Easy to
build, and it makes outcomes global rather than per-request, so two tests cannot run at once and
one test cannot exercise two outcomes. It also makes the *test* responsible for the acquirer's
behaviour, which is one step from the payment service knowing about it.

**A header the caller sets to request an outcome.** Per-request, which fixes the worst of the
above, and it requires the payment service to forward a header it should have no reason to know
about. That is the simulation-shaped hole again.

**Deciding by amount rather than card.** Common in sandboxes, and it collides with the amounts
tests want to use for their own reasons — a test about a large payment would be a test about a
decline whether it meant to be or not.

**Persisting authorizations.** Would survive a restart and make the simulator feel like part of
the platform, which it is not. The lookup returning "no record" after a restart is a state the
platform has to handle anyway.
