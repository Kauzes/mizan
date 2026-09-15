#!/usr/bin/env bash
#
# Takes payments through the gateway under a named load profile and writes down what happened.
#
#   ./scripts/load-profiles.sh              steady, then spike
#   ./scripts/load-profiles.sh soak         thirty minutes, on its own
#   ./scripts/load-profiles.sh steady spike soak
#
# Runs against the Compose stack, already started (docker compose up -d --wait). k6 runs in a
# container on the stack's own network and reaches the gateway by name, the way a client would,
# rather than through a port published to the host.
#
# Each profile writes build/load/<profile>.json, and build/load/machine.txt says what it ran on. A
# number without the hardware it was measured on is not a number anybody can compare against.
#
# Fails if any profile misses a threshold in deploy/load/payments.js, or if the books do not balance
# afterwards: a platform that is fast and loses money has not passed a load test.

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
host() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

if [ "$#" -eq 0 ]; then
    set -- steady spike
fi

K6_IMAGE="$(grep -E '^K6_IMAGE=' "$ROOT/.env" | cut -d= -f2)"
NETWORK="${MIZAN_NETWORK:-mizan_default}"
MERCHANTS="${MIZAN_LOAD_MERCHANTS:-10}"
results="$ROOT/build/load"
mkdir -p "$results"

# The k6 image runs as its own user. On Linux a bind mount keeps the host's ownership, so it would
# not be allowed to write its results; on Docker Desktop the mount does not care.
as_me=()
if [ "$(uname -s)" = "Linux" ]; then
    as_me=(--user "$(id -u):$(id -g)")
fi

# ---------------------------------------------------------------------------------------------
step "The platform is up, and this machine is written down"

call 200 GET "$GATEWAY/actuator/health" > /dev/null
[ -n "$K6_IMAGE" ] || fail "no K6_IMAGE in .env"

{
    printf 'measured   %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'checkout   %s\n' "$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    printf 'cpu        %s\n' "$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//; s/ *$//' || echo unknown)"
    printf 'host cpus  %s\n' "$(nproc 2>/dev/null || echo unknown)"
    printf 'docker     %s\n' "$(docker info --format '{{.OperatingSystem}}, {{.NCPU}} CPUs, {{.MemTotal}} bytes' 2>/dev/null)"
    printf 'k6         %s\n' "$K6_IMAGE"
    printf 'merchants  %s\n' "$MERCHANTS"
    printf 'note       every service, Postgres, Kafka, Redis, the collector and k6 share this one machine\n'
    # What was measured is the images running, which need not be what is checked out: a stack
    # started from one branch keeps its images after switching to another. So each image is named
    # by its id rather than trusted to match the commit above.
    #
    # Asked of the running containers, not of `docker compose images`, which fails outright once a
    # rebuild has replaced an image a container still runs. Best effort: a missing line here is a
    # gap in the record, never a reason to stop measuring.
    printf 'images\n'
    { docker compose -f "$ROOT/docker-compose.yml" ps -q 2>/dev/null \
        | xargs docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} {{slice .Image 7 19}}' 2>/dev/null \
        | sort | sed 's/^/           /'; } || printf '           (could not be listed)\n'
} > "$results/machine.txt"
while IFS= read -r line; do note "$line"; done < "$results/machine.txt"
pass "the gateway answers, and the run is described in build/load/machine.txt"

# ---------------------------------------------------------------------------------------------
missed=()
for profile in "$@"; do
    step "Profile: $profile"

    set +e
    MSYS_NO_PATHCONV=1 docker run --rm "${as_me[@]}" --network "$NETWORK" \
        -v "$(host "$ROOT/deploy/load"):/scripts:ro" \
        -v "$(host "$results"):/results" \
        "$K6_IMAGE" run --quiet \
        -e PROFILE="$profile" \
        -e GATEWAY=http://gateway:8080 \
        -e MERCHANTS="$MERCHANTS" \
        /scripts/payments.js
    status=$?
    set -e

    [ -f "$results/$profile.json" ] || fail "$profile wrote no results (k6 exited $status)"
    if [ "$status" -eq 0 ]; then
        pass "$profile stayed inside its thresholds"
    else
        # Carried on rather than stopping: the books are checked either way, and the next profile's
        # numbers are still worth having.
        note "$profile missed a threshold (k6 exited $status)"
        missed+=("$profile")
    fi
done

# ---------------------------------------------------------------------------------------------
"$(dirname "$0")/books-balance.sh"

if [ "${#missed[@]}" -gt 0 ]; then
    fail "missed thresholds: ${missed[*]} (build/load/*.json says by how much)"
fi
pass "every profile inside its thresholds, and the books balance"
