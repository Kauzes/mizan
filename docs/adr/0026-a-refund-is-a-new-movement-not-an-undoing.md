# ADR 0026: A refund is a new movement, not an undoing

- Status: accepted
- Date: 2026-09-03
- Jira: MIZ-51

## Context

A refund is the first thing on this platform that gives money back, and the first that can
happen more than once to one payment. Both facts make the arithmetic the story rather than the
plumbing, and both were anticipated: ADR 0012 refused to let the journal be edited, ADR 0021
decided the ordering a money-moving call across two systems has to follow, and MIZ-45 left
"releasing money already taken is a refund and not a void" in a test message. This is where all
three get cashed in.

## Decision

**A refund is not a state of the payment.** A payment that has been half refunded is still
captured: the money moved, and that stays true. A status trying to describe both would have to
lie about one of them, so refunds are their own things that happen to a captured payment, and
the payment keeps only the single number the rules depend on.

**Nothing is deleted or edited.** The capture entry stays exactly as it was, and the refund is a
new entry with the opposite postings that *names* the capture it reverses. Both are readable
together, and the history says what happened rather than what somebody wishes had. This is what
ADR 0012's `corrects` field was for; the internal posting route gained support for it here.

**The total refunded can never exceed the captured amount, and the payment row is locked before
that is decided.** Two refunds arriving at once must not each read the same remaining amount and
both conclude there is room — a limit is only a limit if reading it and writing it are one
thing. MIZ-39 learned that the expensive way, where optimistic retries turned out to be a cap on
how many callers an account could have. A test fires ten concurrent requests for a fifth each
and asserts exactly five succeed.

**The rule is written twice.** The service checks it under the lock, so a caller gets a sentence
they can act on; the database checks it with a constraint, which is what holds if the service is
ever wrong. The same doubling as the ledger's balance rule and the payment's
captured-is-recorded rule.

**The acquirer does its own arithmetic.** It refuses to give back more than it took rather than
trusting this platform to have got it right, and when the two disagree the acquirer wins,
because it is the one holding the money.

**Only a captured payment can be refunded.** Releasing a reservation that was never taken is a
void, and the two have different consequences in the books: one posts nothing, the other posts a
reversal.

**A refund in another currency is refused rather than converted.** This platform has no rate,
and inventing one to be helpful is how a refund gives back a different amount of money than was
taken.

**Idempotent on the merchant's own reference**, unique within the payment, and the duplicate
check happens *inside* the payment lock so that two copies of one retry cannot both find
nothing.

**The ledger's external reference is derived from the payment and that reference**, not
generated per attempt. A generated one would let a retry after a lost answer write a second
entry for one refund — the exact failure the ledger's idempotency exists to prevent. This was a
real bug in the first version of this story.

**A refund row exists only once the money has gone back and the books say so.** It is written
after both steps rather than before, so there is no in-flight state — because this story has no
way to resolve one, and a state nothing can move a row out of is a state that strands rows.
`Status` therefore has one value today. MIZ-52 adds the rest along with the machinery that can
resolve them.

## Consequences

**A refund whose ledger call fails leaves the acquirer having given money back that this
platform has not recorded.** The transaction rolls back, the caller is told which, and sending
the refund again finishes it — the acquirer answers a repeat with what it already did, and the
ledger reference is stable so the entry is written once. It is recoverable by repeating, and
nothing repeats it automatically yet. That is MIZ-52, and it is the same window MIZ-45 left open
for capture.

**An acquirer that does not answer leaves the platform not knowing whether the money went back.**
Raised as a timeout rather than guessed at, for the reason MIZ-44 established: deciding it did
not go back would let the merchant refund it a second time, which is the expensive direction to
be wrong in.

**The refunded total is kept on the payment rather than summed from the refunds.** It is the
number the limit is checked against and it has to be read under a lock; summing would mean
locking every refund row instead of one payment row. The cost is a denormalised number that
could in principle disagree with its refunds — the same trade the ledger made for account
balances, and the same reason its integrity check exists.

**`payment.refunded` joins the published events**, carrying both what this refund gave back and
what has been given back in total, so a consumer never has to add them up and can never add them
up wrongly.

**The smoke check finally does what MIZ-26 asked for.** That story's acceptance criteria said the
script should refund a payment; refunds did not exist, so the criteria were corrected and the
gap written down. It is closed here.

## Alternatives considered

**A `REFUNDED` payment status.** Reads well for a full refund and cannot describe a partial one,
which is the common case. A status that is right only sometimes is worse than a number that is
always right.

**Reversing by editing or deleting the capture entry.** The obvious thing, and the reason ADR
0012 made the journal append-only: a ledger whose history can be rewritten cannot be used as
evidence of anything.

**Optimistic locking on the payment instead of a row lock.** Fewer locks held, and under
contention the *n*th concurrent refund needs *n*−1 retries, so the retry budget becomes a limit
on how many refunds a payment can receive at once. Measured and rejected in MIZ-39 for account
balances; the same argument applies here and there was no reason to relearn it.

**Letting the ledger enforce the limit.** It could refuse to credit a settlement account past
the original capture, and it would need to understand what a payment is, which is the coupling
ADR 0021 spent its length avoiding. The ledger records movements; the payment service knows what
they mean.

**Recording failed refunds as rows.** Useful for a merchant asking why nothing happened, and it
means a table where most rows are attempts rather than money. The event and the log carry the
refusal today; if an operator ever needs that history, MIZ-53 is where the question belongs.
