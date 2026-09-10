# ADR 0032: What is normal is learned, not typed in

- Status: accepted
- Date: 2026-09-10
- Jira: MIZ-57

## Context

MIZ-56 scored against numbers somebody chose. A rule that fires above three times ten thousand
is right for a coffee shop and absurd for a car dealer, and no amount of tuning by hand keeps it
right for both as they change. "Unusual" has to mean unusual *for this merchant* or it means
nothing.

## Decision

**Baselines are built from payment events, not from the payment database.** Risk is told what
happened; it does not go and read. That boundary is the reason these are separate services, and
a scorer that reached across it would make the payment service unable to change a column without
breaking fraud detection.

**Through the same inbox as every other consumer**, so an event delivered twice is learned from
once. That matters more here than almost anywhere: a baseline that counted the same payment
three times because the topic hiccuped is a baseline that has quietly moved, and nothing would
say so.

**The observations are kept, not folded into a running total.** Two reasons. A projection has to
be rebuildable and a running total that has forgotten its inputs cannot be; and "how many times
has this card been used here in the last five minutes" is a question about individual payments
that no aggregate answers. A test rebuilds every baseline from the observations and asserts they
come out the same — which is the property that makes this a projection rather than a cache with
better manners.

**The typical amount is a median, not a mean.** One car sold by a coffee shop should not
redefine what a coffee costs, and a mean lets a single outlier do exactly that. It matters here
more than usual, because **the outlier a mean would chase is often the fraud**.

**Only captured payments build the baseline.** What a merchant normally takes is what actually
got taken. Declines are recorded — they say a great deal about a card — and kept out of what is
considered normal, because letting them in is how a burst of fraud attempts teaches the platform
that large refused amounts are ordinary here.

**A baseline from too few payments is not used.** Below ten approved payments the amount rule
stays silent. A median of three is not a measure of anything, and a scorer confidently comparing
against one is worse than a scorer with no opinion, because it is wrong with confidence.

**One payment from a country does not make it familiar.** Two does. Treating the first as a
pattern means the first fraudulent payment from a new country teaches the platform to expect
more of them — the baseline being poisoned by exactly what it exists to catch.

**The rules did not change.** Every lookup in this story was added behind the `WhatWeKnow`
record that MIZ-56 already passed to the scorer, and not a single rule was touched. That was the
reason for making scoring a pure function of what it is given, and it is the concrete payoff.

## Consequences

**The card fingerprint is four digits.** The payment service keeps only the last four, so that
is all there is to work with. One card in ten thousand shares them, which makes this a weaker
signal than a real fingerprint and an honest one: derived from what actually crosses the wire
rather than from a field somebody hoped would be there. It is scoped to the merchant everywhere
it is used, so the collisions that matter are within one merchant's own customers.

**`payment.captured` gained the card's last four**, and a live check is what found it missing.
Risk could only ever see a card on a *declined* payment, so the rule that recognises a returning
customer could never fire — meaning the scorer could only ever grow more suspicious of somebody
the longer they stayed, which is the exact failure that rule exists to prevent. A field added is
not a breaking change, so the payload version stays at one.

**Risk-service had no `handled_event` table and no error handling**, because it had never
consumed anything. Without the table the listener failed on every message; without the handler,
Spring's default retried nine times as fast as it could and moved on, which is both wrong
answers ADR 0025 named. It now uses MIZ-50's arrangement, reused rather than reinvented.

**A merchant whose legitimate behaviour changes overnight will be flagged for a while.** The
median moves as the new payments arrive, so it corrects itself — but the correction is
proportional to how much history it is competing with, and a merchant with three years of
observations will take a long time to be believed about a new line of business. Nothing here
weights recent payments more heavily; that is a real limitation and the obvious next
improvement.

**Nothing prunes the observations.** Every payment this service is told about is kept forever,
which is correct for rebuildability and unbounded in a way that will matter. A retention window
is a decision about how far back "normal" reaches, which is a question worth answering
deliberately rather than by whatever the disk runs out at.

## Alternatives considered

**Reading the payment database directly.** One fewer moving part, no eventual consistency, and
it would couple fraud detection to another service's schema and put risk queries on the database
that authorizations depend on.

**A mean instead of a median.** Cheaper to maintain incrementally — a running sum and a count —
and it is moved arbitrarily far by one outlier, which on this platform is frequently the thing
being detected.

**Keeping only aggregates.** Smaller and faster, and it makes the projection unrebuildable and
the card-velocity question unanswerable. Both of those are the point.

**Treating a merchant with no history as maximally suspicious.** Safe-sounding, and it blocks
the first payment every merchant ever takes. Cold start is the common case on a new platform.

**Exponentially weighted averages**, so recent behaviour counts for more. Genuinely better for
the "merchant changes overnight" case above, and it makes the projection stateful in a way that
depends on the order events arrive in — which at-least-once delivery does not guarantee. Worth
revisiting with a windowed median instead.
