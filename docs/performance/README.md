# Performance

What this platform does under load, measured and written down. How it is measured, and why that
way, is ADR 0054. The script is `scripts/load-profiles.sh`; the profiles are in
`deploy/load/payments.js`.

```sh
docker compose up -d --wait
./scripts/load-profiles.sh              # steady, then spike
./scripts/load-profiles.sh soak         # thirty minutes
```

Every run writes `build/load/<profile>.json` and `build/load/machine.txt`. The run fails if a profile
misses a threshold, or if the books do not balance afterwards.

## The machine

One laptop runs all of it: eight services, Postgres, Kafka, Redis, the trace collector, Prometheus,
Grafana, Tempo, and k6 itself. So these numbers describe that and nothing bigger. They are for
comparing the platform against itself after a change, on this machine, not for sizing a deployment.

| | |
|---|---|
| CPU | AMD Ryzen 5 5600H, 6 cores, 12 threads |
| Memory | 15.4 GiB, of which Docker Desktop has 7.4 GiB and all 12 CPUs |
| Load generator | k6 2.2.0, in a container on the Compose network, 10 merchants |
| Measured | 2026-09-15 |

## One payment

Every iteration is one payment, end to end: **create**, **authorize** with a card the acquirer approves,
and **capture**. Three requests through the gateway, each with its own idempotency key and a real
token, touching the gateway, identity's keys, payment-service, risk, the bank simulator, the ledger and
the outbox. Payments are started at a fixed rate whether or not the last one finished.

## Results

### steady: 30 payments a second for 3 minutes

| | |
|---|---|
| Requests | 16,216, 87 a second |
| Failed | **0.012%** (2 requests) |
| Payments captured | 5,394, **29.1 a second** of 30 offered |
| Payments never started | 4 |
| Books afterwards | balanced: 5,620 entries, 11,240 postings |

| Latency | p50 | p95 | p99 | max |
|---|---|---|---|---|
| create | 13ms | 324ms | 708ms | 1,641ms |
| authorize | 22ms | 389ms | 834ms | 1,688ms |
| capture | 29ms | 454ms | 878ms | 1,577ms |
| **one payment, end to end** | **76ms** | **1,254ms** | **2,160ms** | 3,464ms |

The median is fast and the tail is long. A payment typically completes in 76ms, and one in twenty
takes more than a second: p95 is sixteen times p50. A tail that much wider than the middle is
something stalling occasionally, not everything being slow. **It is explained below.**

### spike: 10 a second, to 120 in 10 seconds, held a minute, back to 10

| | |
|---|---|
| Requests | 27,204, 155 a second |
| Failed | **none** |
| Payments captured | 9,058, 51.7 a second averaged over the whole run |
| Payments never started | **341**, all while the spike was held |
| Books afterwards | balanced: 14,678 entries, 29,356 postings |

| Latency | p50 | p95 | p99 | max |
|---|---|---|---|---|
| create | 723ms | 1,477ms | 1,879ms | 2,970ms |
| authorize | 696ms | 1,449ms | 1,781ms | 2,940ms |
| capture | 699ms | 1,455ms | 1,867ms | 2,734ms |
| **one payment, end to end** | **2,295ms** | **3,801ms** | **4,327ms** | 5,439ms |

**This laptop saturates somewhere between 30 and 120 payments a second.** At 120 a second the platform
could not start 341 payments on time, and latency rose to seconds. It did not fail a single request,
and nothing it did under that overload left the books wrong. That is what the spike profile is for,
and it passed that. Where between 30 and 120 the limit is, and which component reaches it first, is
not yet measured: every service shares one CPU here, so the answer is likely to be the machine before
it is the platform.

What overload looks like, then, is slow rather than broken. Requests queue, and k6 reports the ones it
could not start rather than quietly sending fewer.

### soak: 20 payments a second for 30 minutes

| | |
|---|---|
| Requests | 108,833, 60.3 a second |
| Failed | **none** |
| Payments captured | 36,001, **19.9 a second** of 20 offered |
| Iterations dropped | 0 |
| Books afterwards | balanced: 71,048 entries, 142,096 postings |

| Latency | p50 | p95 | p99 | max |
|---|---|---|---|---|
| create | 8ms | 28ms | 60ms | 1,022ms |
| authorize | 14ms | 35ms | 64ms | 779ms |
| capture | 19ms | 53ms | 104ms | 1,080ms |
| **one payment, end to end** | **42ms** | **101ms** | **239ms** | |

Nothing degraded over the half hour. Heap was where it started — the JVMs were between 47 and 124 MB
at fifteen minutes and between 50 and 108 MB at the end — thread counts moved by single digits, no pool
grew a queue, and the outbox never fell behind. The occasional one-second maximum appears evenly
throughout rather than more often as the run goes on: stalls, not drift.

The interesting number is **p95 at 101ms against a p50 of 42ms — two and a half times**. Steady, at
thirty a second, showed sixteen times. Whatever the tail is, it is not present at twenty a second on
this machine, which is what turned the next section from a guess into a measurement.

## Why the tail was wide

Measured on 2026-09-17, after the soak, on the same machine. The soak had already shown that the tail is
not there at twenty payments a second, so whatever causes it starts between twenty and thirty.

**Every service now publishes latency as buckets** (ADR 0065), which made it possible to ask where the
time went rather than to guess. Running steady again at thirty a second:

| | k6, from outside | the service, from inside |
|---|---|---|
| create, p95 | 514ms | 444ms |
| authorize, p95 | 529ms | 456ms |
| capture, p95 | 705ms | 624ms |

About seven parts in eight of the time was inside payment-service. It was not spent on anybody else:
risk answered in 19ms at p95, the acquirer in 5ms, the ledger in 93ms. It was not garbage collection
either — 0.49 seconds of pauses across five minutes.

It was spent **waiting for a database connection**:

| During the same run | |
|---|---|
| payment-service pool | 10 connections |
| Threads waiting for one, at the peak | **47** |
| Connections in use, at the peak | 10 — the whole pool |

A request holds its connection for its whole life, including while it is waiting on risk, on the
acquirer and on the ledger. This is known and was already reasoned about: the acquirer bulkhead is
sized at eight concurrent calls precisely because "each holds a database connection, so this stays below
the pool" (ADR 0052). What the measurement adds is that at thirty a second the pool, not the acquirer,
is the binding constraint — and that the queue in front of it is where the tail comes from.

### The confirmation, and its catch

The same profile, on the same machine, minutes apart, with only the pool changed:

| | pool of 10 | pool of 40 |
|---|---|---|
| one payment, p50 | 96ms | 68ms |
| **one payment, p95** | **1,792ms** | **239ms** |
| one payment, p99 | 2,621ms | 885ms |
| create, p95 | 514ms | 51ms |
| authorize, p95 | 529ms | 59ms |
| capture, p95 | 705ms | 97ms |
| Threads waiting for a connection | 47 | **0** |
| Connections ever in use | 10 | 15 |
| Thresholds | missed | passed |

Seven and a half times better at p95, from one number. **And it is not the fix.** Postgres reached 99 of
its 100 allowed connections during that run: raising one service's pool spent the platform's whole
connection budget, which is exactly the failure the Helm chart already counts replicas times pool to
avoid. Fifteen connections were ever in use, so the pool needed for this rate is closer to twenty than
to forty — but the demand scales with how long the outbound calls take, which is the property worth
removing rather than sizing around.

The fix is therefore not a bigger number: it is not holding a database connection across a call to
another service. That is a change to transaction boundaries in payment-service, with real consequences
for the payment state machine, and it is **MIZ-105** rather than something to slip into a measurement.

## In CI

`.github/workflows/load.yml` runs steady and spike on main and on changes to the load profiles. A
GitHub runner is a much smaller machine, and the first run there saturated at steady's 30 payments a
second: 24.8 a second captured, 755 never started, about 1.7 seconds per step at the median. So CI
runs steady at **10 a second** (`MIZAN_LOAD_RATE`), against the same latency thresholds. Its numbers
are a regression check, not the ones recorded above.

## Thresholds

A run fails if any is missed:

| | steady and soak | spike |
|---|---|---|
| Requests failed | under 1% | under 1% |
| Checks passed | over 99% | over 99% |
| Books balance afterwards | yes | yes |
| create, p95 | under 500ms | recorded, not held |
| authorize and capture, p95 | under 800ms | recorded, not held |

The latency limits were chosen before the first run and apply to the profiles that claim ordinary
traffic is fast. The spike profile first ran with them too, crossed all three with no failures at all,
and showed the limits were asking it a question it does not ask. It offers four times what this machine
sustains, to see whether overload breaks anything. So its latency is recorded above and not held to a
limit. That change was made after seeing the numbers, and ADR 0054 says so.
