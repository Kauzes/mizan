# ADR 0014: A balance is kept on the account, and guarded by a version

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-37
- Superseded in part: 2026-09-01 by MIZ-39, which replaced the optimistic retry with a row lock. The measurement that forced it is below.

## Context

A balance summed from every posting an account ever had is always correct and gets slower
every day. A balance kept on the account is one row read forever, and is one lost update away
from being a lie that nothing in the system would notice.

## Decision

**The balance is kept on the account, written in the same transaction as the postings that
move it.** The two are never written apart, so there is no window in which the history and the
number disagree.

**A version column guards it.** Two writers that both read a balance and both write it back
would otherwise leave one of the movements out of it. The second write is refused instead, and
the caller's transaction starts over.

**Writers at one account queue on a row lock, taken in a consistent order.** The accounts an
entry touches are locked by id before anything is written, so two entries naming the same pair
the other way round cannot each hold what the other needs.

This replaced an optimistic retry, and the reason is worth keeping. Retrying was bounded at ten
attempts with a randomised backoff, and twelve concurrent writers still exhausted it once the
machine was under the load of a full build. The arithmetic says why, and it is not bad luck:
under read committed a writer updating a contended row blocks until the holder commits and
*then* fails its version check, so with n writers at one account the last one needs n - 1
retries. The retry budget was therefore a limit on how many callers an account could have, which
is not a property anybody chose and not one a caller could know. Raising it would have moved the
threshold rather than removed it.

**The version column stays, and so does the retry**, as a safety net for anything that moves an
account outside the posting path rather than as the way contention is handled.

**Exhausting the retries is its own error.** `CONTENDED`, distinct from the conflict a reused
reference produces, and the message says to send the request again — which is safe, because
every entry carries a reference and a resend is a replay rather than a second posting. Ordinary
posting should never reach it now that writers queue.

**The stored number is the signed sum of the account's postings, debit positive.** It is not
flipped so that a liability reads naturally. A ledger should return one number with one
meaning, and the account's type says which way to read it; presenting it for a person is the
console's job. The balance reading states the currency, the type and the normal side alongside
it so a client has everything it needs to do that.

**A balance is reported with the moment it was read.** A balance is only ever true as at a
moment, and saying so in the response is cheaper than explaining it later.

## Consequences

Every posting now writes to the account row as well as the posting table and holds it for the
length of the transaction, so two entries touching one account serialise where they previously
did not. That is the cost, and it is paid deliberately: serialising is what makes the burst
finish. Transactions here are short — an entry, its postings, and two account rows.

Locking in a consistent order is now a rule any future feature touching two accounts has to
follow. It is enforced by there being one place that locks accounts, and that place sorting.

The twenty-four writer burst the tests drive is more than the old retry budget ever allowed,
which is the point of the number.

The `CONTENDED` code is new in the shared `ErrorCode`, so every service's specification now
documents it whether or not that service can return it. That is the cost of one closed set of
codes across the platform, and it was already the arrangement.

Balances are now a second thing that can drift from the journal. Nothing in this story would
catch a bug that wrote both wrongly in the same transaction — the tests and the code share
their assumptions. MIZ-38 is the check that does not.

## Alternatives considered

**Summing the postings on every read.** Always correct, needs no version column, no retry and
no second thing to keep in step. It also gets slower with every movement, and a balance is the
most read thing in a ledger. The integrity check in MIZ-38 does exactly this sum, which is
what makes keeping the fast copy safe.

**Optimistic locking with a bounded retry.** What this decision originally chose, on the
reasoning that contention on one account would be low and a retry is cheap. Both halves were
true and the conclusion still did not hold: when contention is not low, the retry count becomes
a cap on concurrent callers. Kept as a safety net, no longer the mechanism. The evidence is
above and the change is MIZ-39.

**A separate balances table, or a materialised view refreshed on a schedule.** Reads stay fast
and the journal stays untouched, at the cost of a balance that is right as of the last refresh.
For a payments platform, a balance that is minutes stale is a balance that authorises a payout
that should not have happened.

**Incrementing with `update account set balance = balance + ?`, no version.** Atomic in the
database and immune to lost updates, and it gives up the ability to notice that anything was
concurrent at all — including a future feature that needs to read a balance, decide something,
and write it back. The version column is what makes that decision safe later.
