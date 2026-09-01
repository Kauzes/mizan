# ADR 0012: The journal is append only, and balances by arithmetic the database checks

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-35

## Context

The ledger is the system of record. Everything after this epic — payments, refunds,
settlement, reconciliation — is a claim about money that is only worth what the journal
underneath it is worth. Two properties carry that weight: an entry balances, and nothing ever
changes after it is written.

Both are easy to state and easy to lose. The usual way to lose them is not a wrong algorithm
but an ordinary correction made by an ordinary `UPDATE`, six months later, by somebody solving
a support ticket.

## Decision

**A posting is a signed amount, and a positive amount is a debit.** Whether a debit makes an
account larger is the account type's business, decided in ADR 0011. That separation is what
lets the invariant be plain arithmetic: an entry sums to zero, and no code has to reason about
direction to check it.

**A posting has no currency of its own.** It is in the currency of the account it names, which
is the only arrangement in which the two cannot disagree. The cost is a join to know what a
posting is denominated in, which is what a join is for.

**An entry balances within each currency, not across them.** A single sum over a
multi-currency entry is meaningless and would happen to be zero for the wrong reasons. The
check groups by the account's currency and every group must be zero.

**The invariant is enforced twice, and the second time is the one that counts.** The domain
checks it so the caller learns which currency is out and by how much while the request is
still in hand. The database checks it again with a deferred constraint trigger, and that is the
check that holds against anything writing to the table — a migration, a support script, a
future service, a mistake. A rule that only holds for well behaved application code is a
comment.

**The trigger is deferred to the end of the transaction.** Postings arrive one row at a time,
so an immediate check would refuse the first one every time. It fires on the entry and on each
posting, so postings added to an entry in a later transaction are checked too — the entry
trigger alone would never see them.

**Append only is said by the database.** An `UPDATE` or `DELETE` against `journal_entry` or
`posting` raises. Leaving it to the absence of an endpoint would mean the guarantee holds until
somebody opens a SQL client, which is exactly when it matters.

**A mistake is corrected by a new entry naming the one it corrects.** Both stay visible. That
is not a limitation of this design, it is what a ledger is.

**A merchant's own path cannot post to a platform account.** Accounts are looked up scoped to
the merchant, so a platform account is refused there the same way another merchant's is: it is
not one of this merchant's. Real payments do move money between a merchant and the platform,
and MIZ-4 posts both sides from inside the platform rather than by a merchant reaching through
their own path.

## Consequences

The database refuses things, so the application sees database exceptions rather than clean
domain failures for anything that gets past the domain check. That is the intended order: the
domain check exists to give a good answer, and the trigger exists to be right. The tests assert
on the trigger's message reaching the caller's stack rather than on a tidy exception type,
because a deferred trigger fires at commit and arrives wrapped in whatever was committing.

Nothing can be deleted, including test data and including a mistake made while developing. The
local answer is `docker compose down -v`, the same as for a migration that has already run.

An entry cannot be written with one posting now and its other side later, in a separate
transaction. The trigger would refuse the first transaction at commit. That is correct — a
half-written movement is exactly what this is for — but it means any future feature that wants
to build an entry incrementally has to hold it somewhere else until it is complete.

Postings carry no currency column, so a report that groups by currency joins to `account`.
Acceptable now, and the obvious thing to reconsider if a reporting query ever needs to avoid
the join.

The trigger raises a message rather than returning a constraint name, so translating it into a
problem detail means matching on text. Nothing does that today: the domain check catches every
route a caller can reach, and the trigger exists for the routes a caller cannot.

## Alternatives considered

**Enforcing the invariant only in the domain.** Simpler, faster, and it holds exactly as long
as every writer goes through this service. The whole reason to pay for the trigger is the
writer that does not.

**A `balanced` boolean maintained by the application.** Cheap to query and a lie the moment
anything writes without maintaining it. A derived flag that can disagree with the data is worse
than no flag.

**An immediate rather than deferred trigger, with postings supplied as an array in one
statement.** Would catch an unbalanced entry at the statement rather than at commit, and forces
every writer into a single insert shape. Deferred keeps ordinary inserts ordinary.

**Storing debits and credits as separate positive columns.** The traditional presentation, and
it makes the zero sum check `sum(debit) = sum(credit)` rather than `sum(amount) = 0`. It also
doubles the ways to write a posting, since a negative debit and a positive credit are the same
thing said twice. One signed column has one representation.

**Allowing an update path guarded by a role.** Every ledger that has one eventually uses it.
