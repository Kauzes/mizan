# ADR 0041: No metric carries a merchant, a payment or an amount

- Status: accepted
- Date: 2026-09-14
- Jira: MIZ-76

## Context

MIZ-75 gave every service a `/actuator/prometheus` and a Prometheus that collects it. What
arrived there was almost entirely about the JVM: heap, threads, garbage collection, request
rates by route. All of it true, none of it able to say that a merchant is not being paid.

MIZ-76 adds the numbers that are about the money — authorizations by outcome, declines by the
reason the bank gave, and the length of every queue that waits for a person. Each of those
invites a label that would make it far more useful to whoever is looking at it:

- `merchant` on the authorization counter, so a dashboard can show one merchant's approval rate
- `payment` on the queue gauges, so a chart can be clicked through to the thing that is stuck
- an amount as a gauge or a summary, so the dashboard can show money rather than counts
- the acquirer's decline reason exactly as it arrived, so nothing is lost

Every one of these is a reasonable thing to want, and they have the same two problems.

**Cardinality.** A time series exists for each distinct combination of labels, and is kept for
the retention period whether or not anything ever queries it. A merchant id has no upper bound;
a payment id has no upper bound and a lifetime of seconds. A decline reason is a free text field
on somebody else's API, which means a third party would be deciding how many series this
platform keeps. A monitoring system that falls over does so at the moment it is being used —
during an incident, which is when the series count is highest.

**Disclosure.** A scrape endpoint has no access control here beyond the network (ADR 0040), and
a Prometheus has none at all. `mizan_payments_authorizations_total{merchant="…"}` is one
merchant's approval rate, and their volume, readable by anything on the network. Amounts are
worse: a sum of captures is every merchant's takings added together.

## Decision

**Metrics carry only labels from a small closed set this platform chose. No merchant, no
payment, no card, no reference, no amount.**

- **Outcomes are a fixed vocabulary**: `approved`, `declined`, `held`, `unknown`. Held is
  counted separately from declined on purpose — a decline is between a merchant and a bank, a
  hold is this platform's own decision, and adding them together hides the one anybody here can
  act on.
- **Decline reasons are a named set**, and anything an acquirer sends that is not in it counts
  as `other`. Nothing is lost by that: the reason as it arrived is on the payment and in the
  log, where one row is one event rather than a dimension.
- **Queues are counts and ages, never amounts.** How many captures are waiting is an operational
  fact; what they are worth is a merchant's business.
- **Lag is the age of the oldest thing still waiting**, not an average. An average is reassuring
  during exactly the incident worth noticing, because the items that are stuck are the ones not
  in it yet.
- **This is asserted, not intended.** A test walks every meter in the registry and fails on any
  label from the forbidden set, and the smoke check asks the running Prometheus the same
  question of every series the whole platform actually publishes.

## Consequences

- A per-merchant dashboard cannot be built from these metrics, and should not be: that question
  belongs to the console, which already answers it from the database with the merchant's own
  access control in front of it.
- Getting from "declines are up" to "which payments" means going to traces (MIZ-78) or logs
  (MIZ-79) with the correlation id. That is the intended path, and it is the reason those two
  stories exist.
- `other` will accumulate whatever acquirers invent. If it grows, the fix is to look at the logs
  and add a name deliberately — a decision a person makes, rather than one a third party's API
  makes by sending a new string.
- Every gauge reports zero rather than disappearing when its queue is empty. A metric that is
  absent when all is well is a metric whose alert rule stops evaluating at the same moment.

## Alternatives

- **Label by merchant and rely on retention to bound it.** Retention bounds age, not width. The
  series count grows with the merchant count, and the disclosure problem is unaffected.
- **Label only the largest merchants and bucket the rest.** Bounded, but it makes the platform's
  monitoring depend on a list somebody maintains, and it still publishes those merchants' rates.
- **Pass the acquirer's reason through verbatim.** Hands the cardinality decision to a third
  party, on a field they can change without telling anyone.
- **Publish amounts as gauges.** The most requested number and the least defensible one here:
  every merchant's takings, on an endpoint with no access control, to answer a question the
  console already answers properly.
