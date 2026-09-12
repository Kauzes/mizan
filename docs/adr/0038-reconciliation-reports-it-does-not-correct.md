# ADR 0038: Reconciliation reports a difference, it does not correct one

- Status: accepted
- Date: 2026-09-12
- Jira: MIZ-72

## Context

A day's statement from the acquirer and this platform's own record of that day will not always
agree. Four kinds of disagreement are possible: a transaction this platform has that the
statement does not, one the statement has that this platform never issued, one both have for
amounts that differ, and — the one nobody lists until they meet it — a capture with no acquirer
reference at all, about which nothing can be said.

The tempting design is a job that finds those and fixes them: post an adjusting entry so the
books agree with the bank, mark the difference closed, and leave an operator with an empty
queue. Every payment platform has this pressure, because the queue is real work and the
adjustment is one insert.

## Decision

**Reconciliation compares and records. It never adjusts the books and never writes a difference
off.**

- **A job that can silently make the books match is a job that can silently make them wrong.**
  The two cases are indistinguishable from inside the job. An adjustment that makes a real loss
  disappear looks exactly like one that corrects a rounding error, and the difference between
  them is a fact that lives outside this system — at the bank, or with the customer.
- **The four outcomes are named rather than counted.** A single "failed" figure would hide the
  only information worth having: a transaction the bank never saw and one it saw for the wrong
  amount are different problems, with different fixes and different people to ask.
- **A difference the platform cannot even ask about is its own outcome.** Calling an unreferenced
  capture "missing from the statement" would send somebody looking for it in a file where it
  could never appear. Calling it matched would be a lie. `UNMATCHABLE` is neither.
- **Every difference carries both figures**, so the person who picks it up is not required to go
  and find the other one before they can start.
- **What to do about a difference is a person's decision**, written down as a ruling. That is
  MIZ-73, and it is deliberately a separate story rather than a flag on this one.

## Consequences

- The queue is real work, and it stays real work. That is the point: a platform whose
  reconciliation queue is always empty has either nothing to reconcile or a job that is lying.
- A statement that disagrees with its own trailer is refused rather than reconciled. A file
  truncated in transit looks exactly like a day on which the bank settled less, and reconciling
  it would manufacture a page of differences that are not differences at all. A bank may be
  wrong about this platform; it is not wrong about itself. An unrecognised record type is
  refused for the same reason — a format that has quietly gained one is a format this reader has
  quietly stopped understanding.
- Reconciling is repeatable, and a re-run says the same thing. Each run is its own record
  because it happened, while differences are keyed by what they are about, so a second run finds
  the rows the first one made. The only time anybody reconciles twice is after an incident,
  which is the worst possible moment to be handed a page of duplicates.
- The comparison is on captures and the acquirer's reference, not on batches. A batch is this
  platform's own grouping and the bank has never heard of it.

## Alternatives

- **Auto-adjust below a threshold.** Every threshold is a number somebody chose, and the first
  loss just under it is invisible. A kurus a day across a million payments is a real amount of
  money and a genuine integration bug, and both are hidden by the same rule.
- **Trust the bank and rewrite this platform's record to match.** The bank is authoritative
  about what it settled, not about what this platform captured. Where those differ, the answer
  is usually that something broke here — and overwriting the evidence is how nobody finds out
  what.
- **Reconcile batches instead of captures.** Cheaper to compare, and useless: a batch total that
  matches can be made of transactions that individually do not, and the difference is exactly
  what a person needs to see.
