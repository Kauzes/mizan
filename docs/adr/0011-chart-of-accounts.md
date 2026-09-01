# ADR 0011: What an account is, and who is allowed to have one

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-34

## Context

The ledger is the system of record, and an account is where a number lives. Everything the
rest of this epic does — entries, postings, balances, the integrity check — assumes an answer
to what an account is and what a posting against it means. Those assumptions are cheaper to
write down now than to reverse engineer from four stories of code later.

## Decision

**The sign convention lives in the account type.** In double entry, debit and credit are
directions rather than good and bad news: a debit increases an asset and decreases a liability,
so the same posting means opposite things to two accounts. Putting that on the type means a
caller never says which way an account moves, and cannot say it wrongly.

**A merchant's balance is a liability.** The money is the merchant's, held by the platform, so
paying it out reduces what the platform owes. This is the one that is most easily got backwards
and there is a test that says so out loud.

**An account carries one currency, fixed for its life.** Two currencies in one account means
every read has to know which part is which. A merchant trading in two currencies has two
accounts. Neither the currency nor the type can be changed afterwards, because either would
silently reinterpret history already written against the account.

**The platform's own accounts are seeded by migration**, not created through an API. Every
merchant's money passes through them, they belong to nobody in particular, and a chart of
accounts is a thing somebody decides rather than something that appears because a request
arrived. They have no merchant, which is also why no merchant's path can reach them: the lookup
is scoped by merchant id, so a platform account is simply not found there.

**Adding a currency the platform settles in means another migration.** That is the cost of the
decision above, and it is the intended cost: somebody decides, in a reviewed change, rather than
a clearing account appearing because a request mentioned a new currency.

**`merchant_id` has no foreign key.** Merchants live in the identity database and no service
reads another's tables. The id is the one identity issued, checked at the edge before a request
reaches this service, rather than by a constraint that would need a join across a boundary the
platform does not cross.

**A code is unique within the books it belongs to**, by partial unique index: one for accounts
with a merchant, one for the platform's. Two merchants may both call an account `settlement.try`
without colliding, which they will.

**Reading the books is what a viewer is for.** `ACCOUNT_READ` goes to every role including
`VIEWER` and `ANALYST`; `ACCOUNT_MANAGE` goes to owners and admins. This is the first story that
gives `ADMIN` and `ANALYST` something to do, which ADR 0009 said would happen as endpoints
arrived.

## Consequences

An account cannot be renamed or recoded. Renaming is the safe half of that and could be added;
it was left out because the moment there is an update path, the next request is to change the
type "just this once".

Nothing here stops a merchant opening a hundred accounts, or opening one in a currency the
platform has no clearing account for. The first is not a problem worth solving before it is one;
the second becomes visible in MIZ-35, when an entry needs both sides and one of them is missing.

Seeded platform accounts have fixed ids written into the migration. That makes them addressable
from code and from tests without a lookup by code, and means the migration is the one place they
are defined.

The type set includes `EQUITY`, which nothing uses. It is there because a chart of accounts with
four of the five standard types invites the question of what happens when the fifth is needed,
and the answer should not be a migration under time pressure.

## Alternatives considered

**Debit and credit as a property of each posting rather than the account.** More flexible, and
it makes every caller responsible for knowing which way an account moves. The flexibility is not
wanted: an account's direction is a fact about the account.

**A single account per merchant with a currency column on the postings.** Fewer rows and one
place to look. It also means every balance query has to be told which currency it is asking
about, and a query that forgets returns a number that is the sum of unrelated things.

**Platform accounts created lazily on first use.** No migration per currency, and no operational
step anybody can forget. It also means the chart of accounts grows by accident, and the first
sign of a wrong currency code somewhere is a new account rather than an error.

**A foreign key to a merchants table replicated into this database.** Real referential
integrity, at the cost of a replica to keep in step and a second definition of what a merchant
is. Not worth it for an id that is checked before the request arrives.
