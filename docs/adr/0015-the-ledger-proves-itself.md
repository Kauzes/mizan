# ADR 0015: The ledger is asked to prove itself, by something that shares none of its assumptions

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-38

## Context

Four stories built the ledger and every one of them is tested. All of those tests were written
by the same people as the code they test, from the same understanding of what it should do. A
bug that is consistent about itself — one that writes a balance and its postings wrongly in the
same transaction — looks correct to all of them, because they check that the code does what its
author meant rather than that the result is a ledger.

## Decision

**Two questions, asked of the tables rather than of the code.** The sum of every posting in a
currency is zero, because money is only moved from somewhere to somewhere. And every account's
kept balance is what its own postings add up to, because the fast copy exists only to save the
slow sum. Both have to be true of any ledger, whatever wrote it.

**A failing check names what disagreed and by how much.** The account, its code, what it holds,
what its postings say, and the difference. "The ledger is broken" is not something anybody can
act on at three in the morning; a row and a number is.

**It reads at repeatable read.** The two questions are two statements. At read committed each
would see its own moment, and an entry committed between them would look like drift that is not
there. A check that cries wolf gets switched off, which is worse than not having it.

**It takes no locks and blocks no writes.** A check nobody dares run while the platform is busy
is a check that only runs after something has already gone wrong.

**It is an actuator operation, not an API route.** This is a question about the whole ledger
rather than one merchant's books, so there is no merchant to scope it to and no merchant who
should be asking. Reachable through the gateway's internal route, which has needed a token
since MIZ-30, and not on the public list.

**Deliberately not a health indicator.** Drift should wake somebody, not take the service out
of rotation: a ledger that has drifted is one nobody should be able to write to, and marking it
unhealthy stops the reads too.

## Consequences

The check is O(postings) and takes no locks, so it is cheap now and will not stay cheap forever.
When it stops finishing in a reasonable time the answer is to run it per merchant or per
currency, or over a window, and the shape of the report already allows for that.

It has to be run by something. Nothing schedules it here: that belongs with the operational work
in MIZ-11, alongside somewhere for its output to go. Until then it is a thing an operator can
ask for, which is what the story asked for.

The tests break the ledger on purpose, which means standing down the triggers MIZ-35 added.
That is only possible because the tests own their database; it is not a capability the service
has.

**This check found real drift the first time it was run against the local stack**, in two
accounts, 777 out in opposite directions. The cause was a repair I had run by hand during an
earlier verification, using `limit 1` in a subquery with no `order by`, so the two statements
picked different rows. The check named both accounts and the amount, and the repair was
generated from its output. That is the case this exists for, and it arrived within an hour of
the check existing.

## Alternatives considered

**A scheduled job that alerts.** Where this ends up, and it needs somewhere to alert to, which
the platform does not have yet. The endpoint is the half that can be built now, and a schedule
on top of it later is a small thing.

**A health indicator, so an unhealthy ledger is visible in the existing checks.** Free
visibility, and it would take the service out of the load balancer on drift — stopping the reads
that an operator needs in order to understand the drift.

**Recomputing balances from postings instead of reporting the difference.** Tempting, and wrong
by default: a balance that disagrees with its postings means something wrote one of them
incorrectly, and silently overwriting the evidence removes the only trace of what happened. The
check reports; a person decides.

**Checking on every write.** Correct at all times, and it turns every posting into a scan of the
whole table. The invariant is already enforced per entry by the trigger in MIZ-35; this is about
what those per-entry guarantees cannot see.
