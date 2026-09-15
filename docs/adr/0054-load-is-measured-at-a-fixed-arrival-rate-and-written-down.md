# ADR 0054: Load is offered at a fixed arrival rate, spread across merchants, and the numbers are written down with the machine

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-89

## Context

Every performance claim this platform has made so far was a side effect of another test: the rollout
test (ADR 0051) counted requests in ten-second windows, the autoscaling demonstration watched CPU, the
rate limit's live check timed a quiet merchant. None of them was a measurement of the platform, and
none was written down anywhere a later change could be compared against. The roadmap promises a
benchmark whose numbers are recorded.

A load test is easy to write and easy to write wrongly. The two ways that matter here:

- **Closed-loop load hides a slow platform.** A fixed number of virtual users, each sending its next
  request when the last one answers, sends less when the platform slows down. Throughput falls,
  latency looks tolerable, and the test reports a platform that is merely busy. Real merchants do not
  wait for the previous customer's payment to finish before the next customer pays.
- **One merchant measures the rate limit.** Since ADR 0053 every merchant has an allowance of 100
  requests a second. A load test run as one merchant at any serious rate is refused by design, and
  reports 429s as the platform's performance.

## Decision

**`scripts/load-profiles.sh` runs k6, pinned in `.env`, on the Compose network against the gateway,
under named profiles that offer payments at a fixed arrival rate, spread across ten merchants. Each
profile has thresholds that fail the run, and writes its numbers beside a description of the machine.**

- **Arrival rate, not virtual users.** `constant-arrival-rate` and `ramping-arrival-rate` start a new
  payment on schedule whether or not the last one finished. When the platform cannot keep up, k6
  counts the payments it could not start (`dropped_iterations`) instead of quietly sending fewer.
- **One iteration is one payment, end to end**: create, authorize, and capture if it was authorized,
  each with its own idempotency key, through the gateway with a real token. A held or declined payment
  is not captured, because capturing it would be the invalid request.
- **Three profiles.**

  | Profile | Shape | Question |
  |---|---|---|
  | steady | 30 payments/s for 3 minutes | What does ordinary traffic cost? |
  | spike | 10/s, to 120/s in 10 seconds, held a minute, back to 10/s | Does a sudden surge fail requests, and does it recover? |
  | soak | 20/s for 30 minutes | Does anything accumulate: connections, memory, an outbox falling behind? |

- **Thresholds that fail the run.** Every profile: under 1% of requests failed and 99% of checks passed.
  Steady and soak also: p95 create under 500ms, p95 authorize and capture under 800ms. Chosen before
  the first run, as the rollout budget was, so the numbers could not choose them; the one change made
  since, taking latency limits off the spike profile, is recorded under Results.
- **Ten merchants**, each well inside its allowance at every profile's peak, so the limit is not what
  is measured. Tokens are refreshed inside the run, because the soak outlives a fifteen minute token.
- **The books are checked afterwards**, and the script fails if they do not balance. Fast and wrong
  is not a pass.
- **Written down with the machine.** Each run writes `build/load/<profile>.json` and
  `build/load/machine.txt`: CPU, cores, Docker's share, the k6 image, and the images measured. The
  numbers recorded in `docs/performance/README.md` carry that description.
- **CI runs steady and spike** on main, on demand, and on changes to the load profiles. A GitHub
  runner is a different, shared machine, so its numbers are not the recorded ones; what CI protects is
  the thresholds.

## Results

On one laptop (Ryzen 5 5600H, Docker Desktop with 7.4 GiB), everything on it. Full tables in
`docs/performance/README.md`.

| | steady | spike |
|---|---|---|
| Offered | 30 payments/s | 10 → 120 → 10 payments/s |
| Requests | 16,216 | 27,204 |
| Failed | 0.012% | none |
| Captured | 29.1/s | 51.7/s averaged |
| Never started | 4 | 341 |
| One payment, p50 / p95 / p99 | 76 / 1,254 / 2,160ms | 2,295 / 3,801 / 4,327ms |
| Books afterwards | balanced | balanced |

**Steady passed every threshold, with a long tail.** p95 is sixteen times p50, which reads as occasional
stalls rather than a platform that is slow throughout. Not yet explained.

**Spike saturated the machine.** It crossed all three latency thresholds, with no failed request and
the books balanced, and could not start 341 payments at the peak.

### A threshold decision made after the numbers

The latency thresholds were written before the first run, for every profile. The first spike run
crossed them without failing anything, and that showed they were the wrong question for that profile:
a spike deliberately offers several times what the machine sustains, and asks whether overload breaks
or corrupts anything, not whether it is fast.

So the thresholds are now per profile. Every profile must be correct (under 1% failed, checks passed,
books balanced). Steady and soak must also be fast. Spike's latency is recorded, with a ceiling only a
collapse would reach.

This is exactly the kind of change the rule "chosen before the first run" exists to catch, so it is
written down here rather than made quietly. What it does not do: it does not relax steady, whose
latency limits are unchanged, and it does not hide spike's latency, which is in every result.

## Consequences

- **One laptop runs everything.** The services, Postgres, Kafka, Redis, the collector and k6 itself
  share one CPU. The numbers describe that, not a deployment; they are for comparing this platform
  against itself after a change, on this machine.
- **Thresholds on a shared runner can flake** if a runner is unusually slow. The thresholds leave
  headroom over what was measured here for that reason. A flake is investigated before a threshold
  is loosened.
- **The soak is not in CI.** Thirty minutes on every push to main is expensive for what is mostly a
  question about leaks, better asked before a release than on every merge.

## Alternatives

- **Gatling or JMeter.** Both are capable. k6 scripts are small JavaScript files a reader can follow,
  the image is pinned like every other image here, and arrival-rate executors are first class.
- **Virtual users with think time.** Closed loop, so it under-reports exactly the case a load test
  exists to find.
- **Load from the host through published ports.** Adds Docker Desktop's port forwarding to every
  request. From inside the network the gateway is reached the way the rollout test reached it.
