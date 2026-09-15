# ADR 0051: A rollout is tested while money moves, against a budget written first

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-86

## Context

MIZ-81 to MIZ-85 each made a claim about deploying this platform: images that start cleanly, a
chart that installs, probes that route traffic only to ready pods, graceful shutdown with a pause to
leave the Service, an autoscaler inside a connection budget. Every one of them was checked by
rendering, reading, or running a single process. None of them had been checked the only way the
claim is actually made: a new version rolling out on a cluster while payments are being taken.

That is not a formality. Probes, surge pods, shutdown timing and resource limits interact, and the
interactions only exist when all of them run together under load.

## Decision

**`scripts/rollout-under-load.sh` installs the chart on a kind cluster, takes payments through the
gateway's Service from inside the cluster, rolls every service out to a new version one at a time,
and fails unless the run stays inside a budget that is written in the script before it runs.**

- **The load is inside the cluster.** A pod calling `http://gateway:8080`, the way a client arrives.
  Load from outside through a port-forward would tunnel to one pod — the pod a rollout replaces —
  and would measure the tunnel.
- **Two criteria, both written before the second attempt ran.** At most 0.5% of requests may fail:
  no response, a 5xx, or a 4xx on a request valid for its state. And the slowest ten seconds must
  complete at least a quarter of a typical ten seconds. The second criterion was added after the
  first attempt showed why the first alone is not enough (below). The books must balance afterwards.
- **A held or declined payment is not a failure.** It is the platform working, so it is not
  captured, and capturing it would be the invalid request.
- **Services roll one at a time.** Every Deployment is paused, the new version applied, and each
  resumed and finished before the next. Starting eight JVMs at once on one node is a spike no
  careful rollout chooses.
- **The second version is the same image under a new tag.** A new pod template is all a rollout
  needs; what is under test is the replacing, not what changed.
- **It runs in CI** on main, on demand, and on pull requests that change how the platform is
  deployed — not on every pull request, because it takes around twenty minutes.

## What running it found

The first attempt failed, and each failure was a real defect, fixed before the second:

- **The JVM ran on a quarter of its memory limit.** At the chart's 512Mi that is a 128MiB heap for a
  Spring service. Measured by running the image under the limit, then set to 75 percent in the chart.
- **Every probe had Kubernetes' one second default timeout.** Under the CPU load of a rollout,
  healthy pods took longer than a second to answer their health checks, failed them, and were
  killed; the new pods crash-looped on startup probes; and the rollout took down the version it was
  replacing. Thirty probe timeouts in the events. Probes now wait three to five seconds, and liveness
  needs a full minute without an answer before a restart.
- **The failure count alone would have passed a stall.** The first attempt recorded 8 failures in
  12,660 requests — 0.06%, well inside the budget — while throughput collapsed, because a platform
  that has stopped answering attempts almost nothing. It still failed, on books that could not be
  read. The throughput criterion now catches the stall directly.
- **Kafka's readiness check timed out** for the same one second reason, running a JVM of its own.
- **Injected service variables broke Kafka**, whose image reads every `KAFKA_*` variable as broker
  configuration. The chart now turns service links off for every pod.
- **A pulled image would not load into kind** on Docker Desktop, so the load generator is built from
  the repository.

The second attempt, with all of that fixed:

| | |
|---|---|
| Requests | 35,521 |
| Failed | 2 (0.006%): one 503 on create, one 504 on capture |
| Payments captured end to end | 11,839 |
| Requests per ten seconds | typical 1,263, slowest 662 — 52% of typical |
| Books | balanced |

## The autoscaler, shown

MIZ-85 put payment-service behind an autoscaler and could not show it working without a cluster.
`scripts/autoscaling-on-kind.sh` does, on the cluster the rollout test leaves running, with 32
workers taking payments for four minutes:

- Idle, the autoscaler read 3% CPU across two pods.
- Within about fifteen seconds of load it read 408% and went from two pods to four — its kind
  maximum — in one step, as the scale-up policy allows.
- CPU stayed between 230% and 640% of the request for the whole four minutes with four pods. That
  is the ceiling binding, not the metric: on this laptop, four pods was not enough for that load,
  which is the honest reading of an autoscaler pinned at its maximum.
- When the load stopped, CPU fell to 2%, and after the one minute quiet window it removed a pod,
  and another a minute later, back to two.

It scaled on CPU, the default. Connections in use, the signal ADR 0050 argues actually means load for
this service, needs a metrics adapter this cluster does not run, and remains unshown.

## Consequences

- The two remaining failures are inside the budget and are not explained. A 503 and a 504 during
  eight replacements is consistent with an in-flight request crossing a pod's last moment; it is
  recorded as observed rather than claimed as understood.
- One node, and one replica of most services. The pause-and-resume sequence and the surge pod are the
  same on many nodes; what one node cannot show is a node failing, which is a different test.
- The laptop run needs the Compose stack stopped: Docker has about 7.4GiB here, and the cluster uses
  most of it.
- A rollout that fails this test is a finding, not a flake. Both attempts' events, pods and load
  logs are kept under `build/rollout/` when run locally.

## Alternatives

- **Trust the parts.** Every part had been checked, and the run still found three defects in how
  they combine.
- **Load from outside with a port-forward.** Measures one pod's tunnel, which is severed by exactly
  the event under test.
- **Fail on any failed request.** Two in thirty-five thousand across eight pod replacements is a
  platform working; a zero budget is a test that is eventually switched off.
- **Roll every service at once.** Faster, and on one node it tests CPU starvation rather than the
  rollout.
