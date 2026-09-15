# ADR 0048: One Helm chart installs the services, and points at what it does not own

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-83

## Context

The platform runs on Compose, which is the right tool for a laptop and the wrong one for a
cluster. A cluster needs a description of the same eight services in its own terms:
Deployments, Services, configuration, and credentials that are not written into any file.

That description is a second copy of the platform, and every second copy has the same risk: it
drifts. The ninth service is added to Compose and not to the chart; a port changes in one place;
a service gains a credential and the chart never hands it over, so the service starts and then
fails on its first real request.

## Decision

**One chart, `deploy/helm/mizan`, rendering every service from a single loop over its values.
Postgres, Kafka and the observability stack are dependencies it points at, not things it installs.**

- **One template, not eight.** Each service is an entry in `values.yaml` — port, replicas,
  resources, plain configuration, and the names of the credentials it needs — and one loop
  renders its ConfigMap, Deployment and Service. The same reason MIZ-79 took eight logging blocks
  down to one.
- **No image tag by default, and never `latest`.** An install says which commit it runs. The
  chart refuses to render without `image.tag`, naming it.
- **Credentials only in a Secret, and none with a default.** Either `credentials.existingSecret`
  names a Secret made elsewhere, or every value is supplied at install time. A missing one fails
  the render with its own name, rather than starting a service that falls back to a local
  default. Each service is given only the keys it reads.
- **A pod is locked down by the cluster, not only by the image.** `runAsNonRoot`, uid 65532, no
  privilege escalation, every capability dropped, a read-only root filesystem with an empty
  `/tmp`. The image already runs as nonroot (ADR 0046); saying it again here means a swapped
  image that does not is refused rather than run.
- **No Service lists a management port.** The gateway's actuator on 8090 stays reachable only
  inside the pod network, which is the decision ADR 0040 said would be made again here.
- **Configuration changes roll the pods**, through a checksum of everything a ConfigMap is built
  from. Otherwise new configuration waits for an unrelated restart.
- **Probes are not in this chart yet.** What starting, ready and broken mean for each service, and
  finishing in-flight work before stopping, is MIZ-84 and gets its own attention.

## Consequences

- The chart cannot drift silently from the services. `ChartTest` checks that it lists exactly the
  service modules, that every port matches the port the service listens on, that every credential
  a service reads is one the chart gives it, that no credential appears in plain configuration,
  and that no credential or tag has a default. `scripts/check-chart.sh` renders the chart the
  ways an install can go — complete, without a tag, without credentials, with an existing Secret
  — and checks what comes out.
- Somebody installing this needs a Postgres with the six databases, a Kafka, and an OpenTelemetry
  collector. Owning those would make the chart a worse Postgres chart than the ones that exist.
- The read-only root filesystem has been reasoned about and not yet run: the layers are
  extracted at build time so nothing unpacks at startup, and `/tmp` is writable. MIZ-86 installs
  the chart on a real cluster and is where that is proven or corrected.
- Compose and the chart both describe the platform. Compose stays the fast local path, and the
  tests above are what keep two descriptions honest.

## Alternatives

- **One chart per service.** Eight releases to keep in step for a platform that is only
  meaningful installed together.
- **Plain manifests with Kustomize.** Workable, and the values that differ between installs — tag,
  replicas, credentials — are exactly what Helm's values and `required` handle directly.
- **Include Postgres and Kafka as subcharts.** One command for a demo, and a chart that quietly
  becomes responsible for stateful infrastructure it was never designed to run.
- **Generate the chart from Compose.** Tools exist, and they produce manifests nobody reviews,
  without the security context, the credential scoping or the refusal to render incomplete.
