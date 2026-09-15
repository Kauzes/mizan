# ADR 0046: Service images are distroless, and the healthcheck is the JVM already in them

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-81

## Context

Every service image was Alpine with a JRE, and on top of that curl, a shell and a package
manager. The service uses none of those. An attacker who gets code execution inside a container
uses all of them: a shell to run commands, a package manager to fetch tools, curl to reach
anything the network allows.

Only one thing in the platform used any of it: every Compose healthcheck called curl against
the readiness endpoint. Removing curl means replacing that, in an image that will then have no
shell to run a script in either.

## Decision

**The runtime image is `gcr.io/distroless/java21-debian12:nonroot`, and the healthcheck is a
single compiled Java class run by the JVM the image already has.**

- **Distroless, nonroot variant.** A Java runtime and its libraries, no shell, no package manager,
  running as uid 65532.
- **The healthcheck is `image/Healthcheck.java`**, compiled in the build stage and copied in as a
  12 kB class. An HTTP GET with a three second timeout and an exit code: 2xx is ready, anything
  else including a 503 from an unreachable database is not. The JVM flags on the probe keep a
  check that runs every ten seconds cheap.
- **The jar is split into layers** by how often each one changes, using Boot's own `jarmode=tools`
  extraction. Dependencies are 97 MB and change almost never; the application is under a
  megabyte and changes on every commit, so a code change pushes a layer of kilobytes.
- **Checked against the built image, not the Dockerfile.** Smoke step 24 inspects every running
  service image for its user and tries to start a shell in it.

## Consequences

- Nobody can `docker exec -it payment-service sh` to look around. That is the point, and it is
  also a real loss when debugging. The answer is the observability epic — logs, traces, metrics
  and actuator endpoints — or, when that is not enough, an ephemeral debug container attached to
  the pod, which Kubernetes supports and which leaves the image itself unchanged.
- The image shrank from 163.6 MB to 151.8 MB, about seven percent. That is not what this was
  for: most of an image is the service's dependencies, which no base image changes. The gain is
  in what is absent, and in layers that make a push after a code change small.
- The healthcheck starts a JVM every ten seconds. On a laptop that is a brief, measurable CPU
  blip per service. In Kubernetes the kubelet makes the HTTP request itself and none of this runs
  (MIZ-84), so the cost is confined to Compose.
- The builder stage still uses the JDK on Alpine. It is never shipped, so its contents do not
  reach a running container.

## Alternatives

- **Keep Alpine and remove curl and the shell by hand.** Removes the obvious tools and keeps
  busybox and a package database, and it is one careless line away from coming back.
- **A static healthcheck binary copied from another image.** Works, and adds a second language's
  toolchain and supply chain to the build for a job the JVM already does.
- **No container healthcheck, only Kubernetes probes.** Compose uses the healthcheck to order
  startup (`depends_on: condition: service_healthy`), and that ordering is what makes the
  one-command start work.
- **`jib` or buildpacks instead of a Dockerfile.** Both produce good images, and both hide exactly
  the decisions this ADR exists to make visible.
