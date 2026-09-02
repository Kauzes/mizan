# ADR 0020: A timeout is resolved by asking, never by assuming

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-44

## Context

MIZ-43 left a payment that says `CREATED` while the acquirer holds the money, and said so out
loud. This is the story that closes it.

A call that times out has not failed. It has stopped telling us what happened, which is a
different thing, and the two possibilities behind it are indistinguishable from this side: the
money may be reserved and the reply lost, or nothing may have happened at all.

Both of the available guesses are wrong in a way that costs somebody money. Guessing
"declined" loses the merchant a sale and leaves a reservation nobody releases. Guessing
"approved" ships goods against money that may never have been reserved.

## Decision

**Not knowing is a state.** `AUTHORIZATION_UNKNOWN` is reachable only from a timeout, and it
says exactly what is true: the acquirer was asked and did not answer. It is not final, because
it is a question rather than an outcome.

**The outcome is resolved by asking the acquirer what it did**, keyed on the payment's own id
— which is what the request carried and what a caller who never heard the answer still has.
Never by retrying the authorization, which could reserve the money a second time, and never by
assuming.

**An acquirer with no record is a real answer.** It means nothing was reserved. The payment
stays unresolved rather than being called declined, because it was not declined, and it can
simply be attempted again. That is why `AUTHORIZED` is reachable from the unknown state as well
as from `CREATED`.

**Resolution runs on a schedule.** A merchant should not have to notice that their payment is
in limbo, and a resolution that only happens when somebody asks is one that happens after the
customer has complained.

**The sweep waits a little before asking.** A payment whose call timed out a moment ago may
still be being answered, and asking immediately races a call already in flight for no gain.

**Recording the unknown state is committed on its own.** The timeout is discovered inside the
authorize transaction and the caller is told by an exception, which rolls that transaction
back — so a note written there would go with it, and the payment would go on saying nothing
had been attempted. This is the third time this platform has met that ordering problem, after
MIZ-33 and MIZ-36, and it is written down here as a pattern rather than as a surprise.

**A version column on the payment.** The sweep, a caller's retry, and a late original answer
can all reach one payment, and only one of them may decide it.

## Consequences

The acquirer's test catalogue needed a card that declines slowly, added here rather than in
MIZ-42, because without it every timeout in a test resolves to an approval and a resolver that
simply assumed "approved" would pass. That card is the test that has teeth.

A payment can sit unresolved indefinitely if the acquirer never has a record and nobody retries
it. That is visible and correct — nothing happened — but it means "unresolved" is a state an
operator will eventually want a list of. The partial index is there for that query.

The sweep asks about every unresolved payment on every pass. At the volumes this platform
handles that is nothing; at higher volume it wants a limit and an ordering, and the index
supports both.

Resolution depends on the acquirer keeping a record. MIZ-42's simulator keeps its records in
memory, so a restart makes every unresolved payment look like one nothing happened to. For a
simulator that is fine and the interpretation is still correct; for a real acquirer the
equivalent is a retention window, which is worth knowing before relying on this.

## Alternatives considered

**Retrying the authorization instead of asking about it.** Simpler, one endpoint rather than
two, and it relies entirely on the acquirer's idempotency being real. It is real here because
MIZ-42 made it so, and trusting somebody else's system to deduplicate a money-moving request is
a much larger bet than asking a question with no side effect.

**Treating a timeout as a decline, and letting the customer try again.** What a lot of systems
do. It creates reservations nobody releases and charges customers who were told their payment
failed, on the second attempt, for the first one.

**Blocking the caller until the outcome is known.** Kind to the client, and it holds a request
open for as long as somebody else's system takes, which is unbounded. Answering "not yet known"
and resolving behind the scenes is honest and finishes.

**Resolving only on demand, through an endpoint.** Cheaper, and it makes the merchant
responsible for noticing. The schedule is what makes the state temporary rather than
permanent.
