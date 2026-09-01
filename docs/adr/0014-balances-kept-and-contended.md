# ADR 0014: A balance is kept on the account, and guarded by a version

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-37

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

**Retries are bounded and back off.** Ten attempts, with a short randomised wait that grows.
The bound matters because an unbounded retry under real contention turns a queue into a spin.
The backoff matters more than it looks: the first version retried immediately, and twelve
writers at one account exhausted five attempts — the failures were the retries colliding with
each other, in the same order, at the same moment, rather than with real work. That was
measured, not guessed.

**Exhausting the retries is its own error.** `CONTENDED`, distinct from the conflict a reused
reference produces, and the message says to send the request again — which is safe, because
every entry carries a reference and a resend is a replay rather than a second posting. The two
stories fit together: idempotency is what makes "try again" honest advice.

**The stored number is the signed sum of the account's postings, debit positive.** It is not
flipped so that a liability reads naturally. A ledger should return one number with one
meaning, and the account's type says which way to read it; presenting it for a person is the
console's job. The balance reading states the currency, the type and the normal side alongside
it so a client has everything it needs to do that.

**A balance is reported with the moment it was read.** A balance is only ever true as at a
moment, and saying so in the response is cheaper than explaining it later.

## Consequences

Every posting now writes to the account row as well as the posting table, so two entries
touching the same account serialise where they previously did not. For the expected shape of
traffic — many merchants, few writers per account — that is invisible. For a genuinely hot
account it is the limit of this design, and the answer then is a row lock taken in a
consistent order rather than more retries. That trade is written down here rather than left to
be discovered.

The retry is inside the service, so a caller sees a 409 only when ten attempts failed. Under
the twelve-writer burst the tests drive, none do.

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

**Pessimistic locking, `select ... for update` on the accounts in a consistent order.** No
retries, no contention errors, and it converges under any load rather than degrading. It also
holds locks for the length of the transaction and makes deadlock a thing to be careful about
in every future feature that touches two accounts. Worth revisiting the day an account is hot
enough to notice.

**A separate balances table, or a materialised view refreshed on a schedule.** Reads stay fast
and the journal stays untouched, at the cost of a balance that is right as of the last refresh.
For a payments platform, a balance that is minutes stale is a balance that authorises a payout
that should not have happened.

**Incrementing with `update account set balance = balance + ?`, no version.** Atomic in the
database and immune to lost updates, and it gives up the ability to notice that anything was
concurrent at all — including a future feature that needs to read a balance, decide something,
and write it back. The version column is what makes that decision safe later.
