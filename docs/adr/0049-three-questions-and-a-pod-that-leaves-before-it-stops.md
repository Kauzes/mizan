# ADR 0049: Starting, ready and alive are three questions, and a pod leaves before it stops

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-84

## Context

The chart from MIZ-83 installs the services and tells Kubernetes nothing about them. With no
probes, a pod receives traffic the moment its process exists — before Flyway has migrated, before
the database is reachable — and a stuck process is never restarted. With no shutdown ordering, a
rolling deploy stops old pods while they are answering, and a caller whose authorization is cut
off cannot tell whether the money moved: the platform manufactures an unknown outcome out of an
ordinary deployment.

Probes are also where a well-meant mistake takes a platform down. The tempting liveness check is
"can I reach my database". During a database outage that check fails on every pod of every service
at once, Kubernetes restarts all of them together, they cannot become ready because the database
is still down, and a dependency blip becomes a restart storm.

## Decision

**Three probes with three consequences, graceful shutdown on every service, and a pod that pauses
to leave the Service before it stops.**

- **Starting** — `startupProbe` on liveness, up to three minutes. Until it passes the other probes
  are not asked, so a slow migration is not mistaken for a hung process.
- **Ready** — `readinessProbe` on readiness, which includes the database for every service that
  owns one. Failing it stops traffic and nothing else.
- **Alive** — `livenessProbe` on liveness, which is `livenessState` and nothing more, on every
  service, contributed as a platform default. Failing it restarts the pod, so it may only fail
  when restarting could help.
- **Graceful shutdown with a 20 second phase**, contributed once from `common-web`. Twenty seconds
  covers the slowest request — half a second on risk, five on the acquirer, five on the ledger — and
  the webhook dispatcher now stops claiming and lets the sends already on their way finish and be
  recorded, instead of abandoning them.
- **A five second preStop pause**, using the kubelet's own `sleep` action because the image has no
  shell. Kubernetes removes a stopping pod from the Service and signals the process at the same
  time; the pause is what lets the removal land before the server stops accepting connections.
- **A thirty second grace period** — preStop plus the shutdown phase plus room for the JVM to exit.
  The three numbers live in a values file and a Java constant, and `LifecycleWiringTest` holds them
  in that order.
- **The gateway is probed on its management port**, where its actuator lives (ADR 0040).

## Consequences

- `GracefulShutdownTest` starts a real server, sends a request that is mid-flight when the
  application is closed, and asserts it completes — and asserts the same request is cut off with
  shutdown set to immediate, so the test cannot pass for some other reason.
- The preStop `sleep` action needs Kubernetes 1.30 or later. An older cluster rejects the manifest
  rather than silently skipping the pause, which is the failure worth having.
- A deploy is up to thirty seconds slower per pod on the way out. That is the price of not dropping
  requests, and it is paid by the deploy rather than by a merchant.
- A pod that cannot reach its database stays alive and unready indefinitely. That is correct, and
  it means "not ready for a long time" is an alert condition (MIZ-80's `AServiceIsNotAnswering`
  covers down, not unready — worth revisiting once the chart runs somewhere real).
- Found on the way: the chart's `enabled` flag did nothing, because Helm's `default` treats `false`
  as empty. A service set to `enabled: false` was installed anyway. The render check now disables
  one and counts.

## Alternatives

- **Readiness and liveness on the same endpoint.** Simple, and it is the restart storm described
  above.
- **No startup probe, a long initial delay on liveness.** Works until a migration takes longer than
  the delay on the one day a big one ships.
- **A preStop `exec` running `sleep`.** Needs a shell and a sleep binary in the image, which MIZ-81
  removed on purpose.
- **Rely on Boot's default graceful shutdown and 30 second phase.** The default is graceful today;
  the phase would equal the grace period with no room for the pause or the exit, which is the
  kubelet killing the JVM on every deploy.
