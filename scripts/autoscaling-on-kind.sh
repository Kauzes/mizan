#!/usr/bin/env bash
#
# Shows payment-service's autoscaler growing the service under load and shrinking it afterwards.
#
# The demonstration MIZ-85 could not give without a cluster. Runs against the kind cluster that
# scripts/rollout-under-load.sh leaves running, so the platform is already installed and sized.
#
# What is shown is the mechanism: CPU rises under load, the autoscaler adds pods up to its maximum,
# and after the load stops and the scale-down window passes it removes them. It uses the default CPU
# metric; connections in use needs a metrics adapter this cluster does not run (ADR 0050).

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

CLUSTER=mizan
LOAD_IMAGE=mizan.local/load-generator:local
METRICS_SERVER_VERSION=v0.9.0
WORKERS="${MIZAN_SCALE_WORKERS:-32}"
LOAD_SECONDS="${MIZAN_SCALE_SECONDS:-240}"

kube() { kubectl --context "kind-$CLUSTER" "$@"; }

replicas() { kube get deployment payment-service -o jsonpath='{.status.replicas}'; }
cpu() {
    kube get hpa payment-service \
        -o jsonpath='{.status.currentMetrics[?(@.type=="Resource")].resource.current.averageUtilization}' 2>/dev/null
}

# ---------------------------------------------------------------------------------------------
step "The cluster can measure CPU"

kube get deployment payment-service > /dev/null 2>&1 \
    || fail "no platform in kind-$CLUSTER: run scripts/rollout-under-load.sh first"
kube get hpa payment-service > /dev/null 2>&1 || fail "payment-service has no autoscaler"

kube apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/$METRICS_SERVER_VERSION/components.yaml" > /dev/null
# kind's kubelets serve self-signed certificates, which metrics-server rightly refuses unless told it
# is running somewhere that does not matter.
if ! kube -n kube-system get deployment metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}' \
        | grep -q kubelet-insecure-tls; then
    kube -n kube-system patch deployment metrics-server --type=json \
        -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' > /dev/null
fi
kube -n kube-system rollout status deployment/metrics-server --timeout=180s > /dev/null \
    || fail "metrics-server did not start"
for attempt in $(seq 1 40); do
    [ -n "$(cpu)" ] && break
    sleep 5
done
[ -n "$(cpu)" ] || fail "the autoscaler never received a CPU reading"
start=$(replicas)
floor=$(kube get hpa payment-service -o jsonpath='{.spec.minReplicas}')
ceiling=$(kube get hpa payment-service -o jsonpath='{.spec.maxReplicas}')
pass "the autoscaler reads CPU: $(cpu)% with $start pods (between $floor and $ceiling)"

# ---------------------------------------------------------------------------------------------
step "Load until it grows"

kube delete job scale-load --ignore-not-found > /dev/null
kube apply -f - > /dev/null <<JOB
apiVersion: batch/v1
kind: Job
metadata:
  name: scale-load
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
            - { name: WORKERS, value: "$WORKERS" }
JOB

peak=$start
elapsed=0
while [ "$elapsed" -lt "$((LOAD_SECONDS + 60))" ]; do
    now=$(replicas)
    [ "${now:-0}" -gt "$peak" ] && peak=$now
    note "$(date -u +%H:%M:%S)  cpu $(cpu)%  pods $now"
    if kube get job scale-load -o jsonpath='{.status.succeeded}' 2>/dev/null | grep -q 1; then
        break
    fi
    sleep 15
    elapsed=$((elapsed + 15))
done
[ "$peak" -gt "$start" ] || fail "payment-service never grew past $start pods under load"
pass "grew from $start to $peak pods while $WORKERS workers took payments"

# ---------------------------------------------------------------------------------------------
step "And shrinks back when the load is gone"

window=$(kube get hpa payment-service -o jsonpath='{.spec.behavior.scaleDown.stabilizationWindowSeconds}')
for attempt in $(seq 1 $(( (window + 300) / 15 ))); do
    now=$(replicas)
    note "$(date -u +%H:%M:%S)  cpu $(cpu)%  pods $now"
    [ "$now" = "$floor" ] && break
    sleep 15
done
[ "$(replicas)" = "$floor" ] || fail "payment-service did not come back down to $floor pods"
pass "back to $floor pods after a ${window}s quiet window, one pod at a time"
