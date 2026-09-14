# ADR 0045: An alert names what a person does, and is made to fire before it is trusted

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-80

## Context

MIZ-75 to MIZ-79 made the platform measurable, traceable and readable. None of it reaches a
person who is not already looking. The settlement service has said out loud in its logs since
MIZ-73 that differences have waited too long, and nothing escalated it.

Alerting fails in two directions, and both are quiet about it.

**Too many.** A rule per metric is the easy thing to write. It produces a pager that goes off for
conditions nobody would act on, and a pager nobody answers is worse than none: the one alert
that matters arrives trained away.

**Too few, silently.** A rule with a misspelt metric name, or a threshold on the wrong side, or a
`for` longer than the condition ever lasts, never fires. A rule that never fires is
indistinguishable from a platform that is fine, and it is discovered during the incident it was
written for.

## Decision

**Five rules, each naming a person's action, each shown the condition it exists for and the
nearest harmless one before it is trusted.**

- **The two non-negotiable ones page.** The ledger not balancing, immediately, because a drifted
  ledger does not fix itself and waiting only lets more postings land on the wrong one. An event
  set aside for ten minutes, because that is something a merchant should have been told and was
  not — ten rather than zero, because an operator redelivering by hand passes through the state.
- **A difference unruled for a day raises a ticket**, not a page. It is serious and it is not a
  three in the morning problem; the difference between the two is part of the rule.
- **A collapse in the approval rate pages**, but only when enough was attempted to mean anything.
  It is the shape of an acquirer outage and of a bad deployment, and it is the rule that will fire
  for a reason nobody predicted.
- **A service down for two minutes pages**, and a rolling restart does not.
- **Every rule carries its action in an annotation, in a sentence.** An alert whose runbook is
  "ask whoever wrote it" has a single point of failure, and that person is asleep too.
- **Every rule is tested by Prometheus's own evaluator.** `alerts-test.yml` feeds each one
  synthetic series and asserts it fires, and feeds it the harmless neighbour and asserts it does
  not. The smoke check runs those tests inside the Prometheus the stack runs, then asks that
  Prometheus whether it loaded the rules. `AlertsTest` refuses a rule naming a metric no service
  registers, and a rule without a severity, a summary or an action.

## Consequences

- Things that are interesting and not actionable — a slow request, a rising error count, a
  webhook backlog that is draining — are on the dashboards (MIZ-77) and deliberately not here.
  The test caps the file at a dozen rules to keep the argument having to be made.
- There is no Alertmanager in this stack, so an alert fires in Prometheus and reaches nobody.
  Routing — who is paged, how, and when a ticket is opened instead — is a fact about an
  organisation rather than about this repository, and it belongs with the deployment work.
- The thresholds are judgements: half of authorizations, twenty attempts, a day. They are written
  beside the rule with the reason, so the next person to change one argues with the reason rather
  than with a number.
- On a long-lived local stack, the dead letter and reconciliation alerts will be firing, because
  that stack really does hold a set-aside event and differences nobody has ruled on. That is the
  alerts being right.

## Alternatives

- **A rule per metric, severity decided later.** Every monitoring setup starts here and every
  on-call rotation learns to ignore it.
- **Test the rules by reading them.** Catches the misspelt name and not the threshold on the wrong
  side, the `for` that never elapses, or the `and` that can never be true at the same time as the
  condition. Only an evaluator catches those.
- **Alert on the settlement log line.** The platform already writes it; a log-based alert needs a
  log pipeline this stack does not have, and the gauge from MIZ-76 says the same thing as a number.
