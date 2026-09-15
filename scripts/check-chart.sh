#!/usr/bin/env bash
#
# Renders the Helm chart the ways an install can go, and fails if any of them is wrong.
#
# Needs only Docker: Helm runs from a pinned image, so neither CI nor a laptop has to install it.
# ChartTest checks the chart's values against the services; this checks what Helm actually
# produces from them, which is the thing a cluster receives.

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

HELM_IMAGE="${HELM_IMAGE:-alpine/helm:3.19.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHART="$ROOT/deploy/helm/mizan"

host() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

helm() {
    MSYS_NO_PATHCONV=1 docker run --rm -v "$(host "$CHART"):/chart:ro" "$HELM_IMAGE" "$@"
}

# Stand-ins for what an install supplies. Obviously not real, and passed on the command line
# rather than written into any file.
supplied=(--set image.tag=0123abc
          --set credentials.databasePassword=not-a-real-password
          --set credentials.internalServiceToken=not-a-real-token
          --set credentials.apiKeyEncryptionKey=bm90LWEtcmVhbC1rZXk=
          --set credentials.webhookEncryptionKey=bm90LWEtcmVhbC1rZXk=
          --set credentials.jwtPrivateKey=not-a-real-pem)

services=$(ls -d "$ROOT"/services/*/src/main/resources/application.yml | wc -l | tr -d ' ')

step "The chart renders, and refuses to render without what it needs"

helm lint /chart "${supplied[@]}" > /dev/null || fail "helm lint found a problem"
pass "helm lint is clean"

rendered=$(helm template mizan /chart "${supplied[@]}")
summary=$(printf '%s' "$rendered" | "$PYTHON" -c '
import re, sys
docs = [d for d in sys.stdin.read().split("\n---") if d.strip()]
kinds = {}
exposed = []
for d in docs:
    kind = re.search(r"^kind: (\w+)", d, re.M)
    if not kind:
        continue
    kinds[kind.group(1)] = kinds.get(kind.group(1), 0) + 1
    if kind.group(1) == "Service" and re.search(r"port: 8090\b", d):
        exposed.append(re.search(r"^  name: (\S+)", d, re.M).group(1))
print(kinds.get("Deployment", 0), kinds.get("Service", 0), kinds.get("ConfigMap", 0),
      kinds.get("Secret", 0), ",".join(exposed) or "-")')
read -r deployments svcs configmaps secrets exposed <<< "$summary"

[ "$deployments" = "$services" ] || fail "$deployments Deployments for $services services"
[ "$svcs" = "$services" ] || fail "$svcs Services for $services services"
[ "$configmaps" = "$services" ] || fail "$configmaps ConfigMaps for $services services"
[ "$secrets" = "1" ] || fail "$secrets Secrets rendered, expected the one credentials Secret"
pass "one Deployment, Service and ConfigMap for each of the $services services, and one Secret"

[ "$exposed" = "-" ] || fail "a Service exposes a management port: $exposed (ADR 0040)"
pass "and no Service exposes a management port"

printf '%s' "$rendered" | grep -q 'image: ".*:0123abc"' || fail "images are not tagged by the supplied commit"
if printf '%s' "$rendered" | grep -q ':latest"'; then fail "an image is tagged latest"; fi
pass "every image is the commit that was asked for, and none is latest"

without_tag=$(helm template mizan /chart "${supplied[@]:2}" 2>&1 || true)
case "$without_tag" in
    *"image.tag is required"*) pass "without an image tag it refuses, and says so" ;;
    *) fail "rendering without an image tag did not refuse by name: $without_tag" ;;
esac

without_credentials=$(helm template mizan /chart --set image.tag=0123abc 2>&1 || true)
case "$without_credentials" in
    *"credentials."*"is required"*) pass "without credentials it refuses, naming the missing one" ;;
    *) fail "rendering without credentials did not refuse by name: $without_credentials" ;;
esac

existing=$(helm template mizan /chart --set image.tag=0123abc \
    --set credentials.existingSecret=supplied-elsewhere)
if printf '%s' "$existing" | grep -q '^kind: Secret'; then
    fail "the chart created a Secret even though an existing one was named"
fi
printf '%s' "$existing" | grep -q 'name: supplied-elsewhere' \
    || fail "the services do not read from the Secret that was named"
pass "and with an existing Secret named, it creates none and reads from that one"

step "Every pod knows when it is starting, ready and alive, and leaves before it stops"

lifecycle=$(printf '%s' "$rendered" | "$PYTHON" -c '
import re, sys
# kind is matched at the start of a line: an autoscaler names its target as an indented
# "kind: Deployment", and matching the substring read it as a Deployment with no probes.
deployments = [d for d in sys.stdin.read().split("\n---") if re.search(r"^kind: Deployment$", d, re.M)]
problems = []
for d in deployments:
    name = re.search(r"^  name: (\S+)", d, re.M).group(1)
    for needed in ("startupProbe:", "readinessProbe:", "livenessProbe:", "preStop:",
                   "terminationGracePeriodSeconds:"):
        if needed not in d:
            problems.append("%s has no %s" % (name, needed.rstrip(":")))
    ports = set(re.findall(r"port: (http|management) ", d))
    expected = {"management"} if name == "gateway" else {"http"}
    if ports != expected:
        problems.append("%s is probed on %s, expected %s" % (name, sorted(ports), sorted(expected)))
print("\n".join(problems) or "-")')
[ "$lifecycle" = "-" ] || fail "$lifecycle"
pass "every Deployment has startup, readiness and liveness probes, a preStop pause and a grace period"
pass "and the gateway is probed on its management port, everybody else on http"

without_simulator=$(helm template mizan /chart "${supplied[@]}" \
    --set services.bank-simulator.enabled=false | grep -c '^kind: Deployment' || true)
[ "$without_simulator" = "$((services - 1))" ] \
    || fail "disabling bank-simulator still rendered $without_simulator Deployments"
pass "and a service set to enabled: false is not installed"

step "The payment service grows under load, and nothing grows past what Postgres can serve"

scaling=$(printf '%s' "$rendered" | "$PYTHON" -c '
import re, sys
docs = [d for d in sys.stdin.read().split("\n---") if d.strip()]
problems = []
scaled = [re.search(r"^  name: (\S+)", d, re.M).group(1) for d in docs
          if re.search(r"^kind: HorizontalPodAutoscaler$", d, re.M)]
if scaled != ["payment-service"]:
    problems.append("autoscalers rendered for %s, expected payment-service only" % scaled)
for d in docs:
    if not re.search(r"^kind: Deployment$", d, re.M):
        continue
    name = re.search(r"^  name: (\S+)", d, re.M).group(1)
    has_replicas = re.search(r"^  replicas: ", d, re.M) is not None
    if name == "payment-service" and has_replicas:
        problems.append("payment-service sets replicas, so helm upgrade would fight its autoscaler")
    if name != "payment-service" and not has_replicas:
        problems.append("%s has no replica count" % name)
for d in docs:
    if not re.search(r"^kind: ConfigMap$", d, re.M) or "MIZAN_DB_URL" not in d:
        continue
    name = re.search(r"^  name: (\S+)", d, re.M).group(1)
    if "SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE" not in d:
        problems.append("%s owns a database and its pool is left at the default" % name)
print("\n".join(problems) or "-")')
[ "$scaling" = "-" ] || fail "$scaling"
pass "only payment-service has an autoscaler, and its Deployment leaves the count to it"
pass "and every service that owns a database has its pool sized against the budget"
