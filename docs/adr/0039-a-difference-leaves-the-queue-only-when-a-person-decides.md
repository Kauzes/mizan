# ADR 0039: A difference leaves the queue only when a person decides

- Status: accepted
- Date: 2026-09-12
- Jira: MIZ-73

## Context

MIZ-72 made reconciliation report four kinds of difference and refuse to adjust anything, which
leaves a list. ADR 0038 says nothing may be written off automatically. The open question is what
makes an item leave that list, and there is an obvious cheap answer: drop a difference when a
later run no longer finds it. Re-reconcile tomorrow, the transaction settles overnight, the row
disappears, and the queue stays short without anybody doing anything.

It is cheap because it is usually right. It is wrong in exactly the cases the queue exists for.

## Decision

**A difference is outstanding until somebody rules on it, and nothing else takes it off the
list.** Outstanding is derived from whether a ruling exists — there is no "resolved" flag —
so there is one fact rather than two that can disagree.

- **A later run that does not see it does not close it.** A difference that stopped being
  reported is not the same as a difference that was explained. The transaction may have settled
  late, or the statement may have been regenerated, or this platform may have lost the record it
  was comparing against. The row stays, and says that the newest run no longer reports it,
  because "it went away" is something a person needs to be told rather than a reason to stop
  telling them.
- **Two rulings and no more.** `ACKNOWLEDGED` — a person looked, understands it, nothing about
  the money changes. `CORRECTED` — an entry was posted in the ledger, and the ruling names it.
  There is deliberately no `WRITTEN_OFF`: a write-off by another name is still the feature that
  makes a ledger untrustworthy.
- **Ruling never moves money**, which is the same line MIZ-53 drew for stuck payments. An
  endpoint that posted an entry on an operator's say-so would be a way to write the books by
  hand, and that is what a double entry ledger exists to make impossible.
- **A correction is checked, not believed.** The ledger is asked whether the entry exists and
  whose books it is in, and a ruling naming an entry that does not exist — or one in somebody
  else's books — is refused. A decision recorded as evidence has to be evidence.
- **Every ruling is kept**, append only, with who made it and why, both required. Somebody who
  acknowledged a difference on Monday and corrected it on Thursday did two things, and a record
  that kept only the second is a record of a decision nobody took.
- **The platform says so without being asked.** A scheduled sweep logs what is waiting and how
  long the oldest has waited, louder once it has gone past the platform's patience. An endpoint
  only answers somebody who thought to look, and the failure worth guarding against is nobody
  looking.

## Consequences

- The queue grows if nobody works it, and the logs get louder. That is the honest failure mode:
  the alternative designs all make an unworked queue look like a worked one.
- An operator correcting a difference does two things rather than one — post the entry, then
  record the ruling that names it. That is more work than a button, and it is the same amount of
  work the books require of everybody else.
- A difference that genuinely resolves itself still needs a sentence from a person. Cheap to
  write, and it turns "it disappeared" into "the acquirer settled it a day late", which is the
  thing somebody will want to know the next time it happens.

## Alternatives

- **Auto-close when a later run agrees.** Closes the real cases and the dangerous ones with the
  same rule, silently, and leaves nobody to notice that the record this platform was comparing
  against is the thing that changed.
- **An age after which a difference expires.** A timer is not a decision, and the differences
  that survive longest are the ones nobody understood.
- **A health indicator that goes down while differences are waiting.** A bookkeeping difference
  is a question for a person, not a reason to take a healthy service out of a load balancer — and
  a service marked down gets restarted, which fixes nothing and loses the queue nobody read.
