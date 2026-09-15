#!/usr/bin/env bash
#
# Rolls out a new version of the platform while payments are being taken, and fails if more
# requests fail than the error budget allows or the books stop balancing (MIZ-86).
#
# Everything happens inside a kind cluster: the dependencies, the chart, and the load. The load runs
# as a pod calling the gateway's Service, the way a real client arrives. Load sent from outside
# through a port-forward would tunnel to a single pod — the very pod a rollout replaces — and the
# test would be measuring the tunnel.
#
# The error budget is written below, before anything runs, as a number. Changing it after seeing a
# result is not a passing test.
#
#   ./scripts/rollout-under-load.sh            leaves the cluster up for inspection
#   KEEP_CLUSTER=false ./scripts/rollout-under-load.sh
#
# Needs Docker, kubectl, and kind (build/tools/kind.exe is used if present). Helm runs from a pinned
# image. Stop the Compose stack first on a machine with less than about 12 GiB for Docker.

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

# ---------------------------------------------------------------------------------------------
# The budget. At most this percentage of requests made during the whole run — before, during and
# after the rollout — may fail. Half a percent: a rolling deploy that drops more than one request in
# two hundred is a deploy merchants notice. ADR 0051 says why this number.
ERROR_BUDGET_PERCENT="${MIZAN_ERROR_BUDGET_PERCENT:-0.5}"
# And it keeps serving. The slowest ten seconds of the run must complete at least this percentage of
# a typical ten seconds. Added after the first attempt, before the second: a platform that stalls
# attempts so few requests that its failure percentage stays tiny, and a budget measured by failures
# alone would have passed it.
KEEPS_SERVING_PERCENT="${MIZAN_KEEPS_SERVING_PERCENT:-25}"
# ---------------------------------------------------------------------------------------------

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLUSTER=mizan
KIND="${KIND:-$ROOT/build/tools/kind.exe}"
command -v "$KIND" > /dev/null 2>&1 || KIND=kind
HELM_IMAGE="${HELM_IMAGE:-alpine/helm:3.19.0}"
LOAD_IMAGE=mizan.local/load-generator:local
LOAD_SECONDS="${MIZAN_LOAD_SECONDS:-300}"
LOAD_WORKERS="${MIZAN_LOAD_WORKERS:-6}"
ROLL_AFTER_SECONDS="${MIZAN_ROLL_AFTER_SECONDS:-90}"
KEEP_CLUSTER="${KEEP_CLUSTER:-true}"
SERVICES=(gateway identity-service ledger-service payment-service risk-service
          notification-service settlement-service bank-simulator)
FIRST=rollout-first
SECOND=rollout-second

host() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
work="$ROOT/build/rollout"
mkdir -p "$work"

helm() {
    MSYS_NO_PATHCONV=1 docker run --rm --network kind \
        -v "$(host "$work/kubeconfig"):/root/.kube/config:ro" \
        -v "$(host "$ROOT/deploy"):/deploy:ro" \
        "$HELM_IMAGE" "$@"
}

kube() { kubectl --context "kind-$CLUSTER" "$@"; }

# ---------------------------------------------------------------------------------------------
step "A cluster to deploy onto"

if "$KIND" get clusters 2> /dev/null | grep -qx "$CLUSTER"; then
    note "reusing the kind cluster named $CLUSTER"
else
    "$KIND" create cluster --config "$(host "$ROOT/deploy/kind/cluster.yaml")" --wait 120s > /dev/null
fi
"$KIND" get kubeconfig --internal --name "$CLUSTER" > "$work/kubeconfig"
pass "kind cluster $CLUSTER is up, $(kube version -o json | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')"

# ---------------------------------------------------------------------------------------------
step "The images, as two versions"

# Two tags on the images Compose built. The second is the same content under a new name, which is
# all a rollout needs: a new pod template, so Kubernetes replaces every pod. What is under test is
# the replacing, not what changed.
for service in "${SERVICES[@]}"; do
    docker image inspect "mizan-$service:latest" > /dev/null 2>&1 \
        || fail "no image mizan-$service:latest: run docker compose build first"
    docker tag "mizan-$service:latest" "mizan.local/mizan-$service:$FIRST"
    docker tag "mizan-$service:latest" "mizan.local/mizan-$service:$SECOND"
    "$KIND" load docker-image --name "$CLUSTER" \
        "mizan.local/mizan-$service:$FIRST" "mizan.local/mizan-$service:$SECOND" > /dev/null
done
# Built from deploy/kind/load rather than pulled: a pulled multi-platform image does not load into
# kind on Docker Desktop, and a local build does (see the Dockerfile there).
docker build -q -t "$LOAD_IMAGE" "$(host "$ROOT/deploy/kind/load")" > /dev/null
"$KIND" load docker-image --name "$CLUSTER" "$LOAD_IMAGE" > /dev/null
pass "eight services loaded as $FIRST and $SECOND, and the load generator's image"

# ---------------------------------------------------------------------------------------------
step "What the platform depends on"

jwt_key="$work/jwt.pem"
[ -f "$jwt_key" ] || openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$jwt_key" 2> /dev/null
kube create secret generic mizan-credentials \
    --from-literal=databasePassword=rollout-test-only \
    --from-literal=internalServiceToken=rollout-test-only \
    --from-literal=apiKeyEncryptionKey=bWl6YW4tbG9jYWwtZGV2ZWxvcG1lbnQtb25seS1rZXk= \
    --from-literal=webhookEncryptionKey=bWl6YW4td2ViaG9vay1zaWduaW5nLWxvY2FsLWtleSE= \
    --from-file=jwtPrivateKey="$(host "$jwt_key")" \
    --dry-run=client -o yaml | kube apply -f - > /dev/null
kube create configmap postgres-init \
    --from-file=10-databases.sql="$(host "$ROOT/deploy/local/init-databases.sql")" \
    --dry-run=client -o yaml | kube apply -f - > /dev/null
kube apply -f "$(host "$ROOT/deploy/kind/dependencies.yaml")" > /dev/null
for dependency in postgres kafka otel-collector; do
    kube rollout status "deployment/$dependency" --timeout=300s > /dev/null \
        || fail "$dependency did not become ready"
done
pass "postgres, kafka and a trace collector are ready"

# ---------------------------------------------------------------------------------------------
step "The platform, version one"

helm upgrade --install mizan /deploy/helm/mizan \
    -f /deploy/kind/values-kind.yaml --set image.tag="$FIRST" --wait=false > /dev/null
for service in "${SERVICES[@]}"; do
    kube rollout status "deployment/$service" --timeout=600s > /dev/null \
        || fail "$service did not become ready on $FIRST"
done
pass "all eight services are ready on $FIRST"

# ---------------------------------------------------------------------------------------------
step "Payments, while a new version rolls out"

kube delete job load --ignore-not-found > /dev/null
kube apply -f - > /dev/null <<JOB
apiVersion: batch/v1
kind: Job
metadata:
  name: load
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      enableServiceLinks: false
      containers:
        - name: load
          image: $LOAD_IMAGE
          imagePullPolicy: IfNotPresent
          env:
            - { name: GATEWAY, value: "http://gateway:8080" }
            - { name: LEDGER, value: "http://ledger-service:8082" }
            - { name: SECONDS, value: "$LOAD_SECONDS" }
            - { name: WORKERS, value: "$LOAD_WORKERS" }
JOB

kube wait --for=condition=ready pod -l job-name=load --timeout=120s > /dev/null \
    || fail "the load generator did not start"
note "load running for ${LOAD_SECONDS}s with $LOAD_WORKERS workers; rolling out after ${ROLL_AFTER_SECONDS}s"
sleep "$ROLL_AFTER_SECONDS"

rolled_at=$(date -u +%H:%M:%S)
# One service at a time. Every Deployment is paused, the new version is applied to all of them, and
# each is resumed and allowed to finish before the next starts. Starting eight JVMs at once on one
# node is a load spike no real rollout would choose, and the one this test exists to survive is the
# ordinary kind.
for service in "${SERVICES[@]}"; do
    kube rollout pause "deployment/$service" > /dev/null
done
helm upgrade mizan /deploy/helm/mizan \
    -f /deploy/kind/values-kind.yaml --set image.tag="$SECOND" --wait=false > /dev/null
for service in "${SERVICES[@]}"; do
    kube rollout resume "deployment/$service" > /dev/null
    kube rollout status "deployment/$service" --timeout=600s > /dev/null \
        || fail "$service did not finish rolling out to $SECOND"
    note "$service is on $SECOND"
done
pass "every service rolled out to $SECOND (started $rolled_at), with payments being taken throughout"

kube wait --for=condition=complete job/load --timeout="$((LOAD_SECONDS + 300))s" > /dev/null \
    || { kube logs job/load | tail -20 >&2; fail "the load generator did not finish"; }
summary=$(kube logs job/load | grep '^SUMMARY ' | tail -1 | sed 's/^SUMMARY //')
[ -n "$summary" ] || { kube logs job/load | tail -20 >&2; fail "the load generator wrote no summary"; }

# ---------------------------------------------------------------------------------------------
step "Against the budget"

printf '%s' "$summary" | "$PYTHON" -c '
import json, sys
s = json.load(sys.stdin)
print("  requests  %d" % s["requests"])
print("  failed    %d (%.3f%%)" % (s["failed"], 100.0 * s["failed"] / max(s["requests"], 1)))
for kind, n in sorted(s["failures"].items(), key=lambda kv: -kv[1]):
    print("    %-40s %d" % (kind, n))
print("  payments captured end to end  %d" % s["captured"])
w = s["windows"][1:-1] or s["windows"]
print("  requests per 10s: typical %d, slowest %d" % (sorted(w)[len(w) // 2], min(w)))
'
verdict=$(printf '%s' "$summary" | "$PYTHON" -c '
import json, sys
s = json.load(sys.stdin)
budget = float(sys.argv[1])
serving = float(sys.argv[2])
spent = 100.0 * s["failed"] / max(s["requests"], 1)
w = s["windows"][1:-1] or s["windows"]
typical = sorted(w)[len(w) // 2]
slowest_percent = 100.0 * min(w) / max(typical, 1)
if s["requests"] < 500:
    print("too few requests to mean anything")
elif s["books"] != "sound":
    print("books: " + s["books"])
elif spent > budget:
    print("spent %.3f%% of a %.3f%% budget" % (spent, budget))
elif slowest_percent < serving:
    print("stalled: the slowest ten seconds completed %.0f%% of a typical ten, below %.0f%%"
          % (slowest_percent, serving))
else:
    print("ok")' "$ERROR_BUDGET_PERCENT" "$KEEPS_SERVING_PERCENT")
[ "$verdict" = "ok" ] || fail "$verdict"
pass "within the ${ERROR_BUDGET_PERCENT}% error budget, never below ${KEEPS_SERVING_PERCENT}% of normal throughput, and the books balance"

if [ "$KEEP_CLUSTER" = "false" ]; then
    "$KIND" delete cluster --name "$CLUSTER" > /dev/null
    note "cluster deleted"
else
    note "cluster left running: kubectl --context kind-$CLUSTER get pods ; $KIND delete cluster --name $CLUSTER"
fi
