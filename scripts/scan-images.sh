#!/usr/bin/env bash
#
# Scans every service image the stack built, and fails on anything serious that has a fix.
#
# The same script CI runs, so a developer can see what CI will see before pushing. It scans the
# images Compose already built rather than building its own, because the image worth scanning
# is the one that was tested — a rebuild of the same source is a different image the moment a
# base layer moves.
#
# Fails on HIGH or CRITICAL findings with a fix available. A finding with no fix cannot be acted
# on and would only teach people to ignore the scan. Accepted findings, with their reasons and
# their deadlines, are in .trivyignore.yaml.

set -euo pipefail

TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.67.2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

services=(gateway identity-service ledger-service payment-service risk-service
          notification-service settlement-service bank-simulator)

# One vulnerability database for all eight scans, downloaded once.
cache="${TRIVY_CACHE:-$ROOT/build/trivy-cache}"
mkdir -p "$cache"

# Git Bash on Windows needs host paths in Windows form for a volume mount.
host() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

failed=()
for service in "${services[@]}"; do
    image="mizan-$service:latest"
    if ! docker image inspect "$image" > /dev/null 2>&1; then
        echo "no image $image to scan: run docker compose build first" >&2
        exit 1
    fi
    echo "== $image"
    if ! MSYS_NO_PATHCONV=1 docker run --rm \
            -v /var/run/docker.sock:/var/run/docker.sock \
            -v "$(host "$cache"):/root/.cache/trivy" \
            -v "$(host "$ROOT"):/repo:ro" \
            "$TRIVY_IMAGE" image \
            --quiet \
            --scanners vuln \
            --severity HIGH,CRITICAL \
            --ignore-unfixed \
            --ignorefile /repo/.trivyignore.yaml \
            --exit-code 1 \
            "$image"; then
        failed+=("$service")
    fi
done

if [ "${#failed[@]}" -gt 0 ]; then
    echo >&2
    echo "serious, fixable findings in: ${failed[*]}" >&2
    echo "fix them, or accept one with a reason and an end date in .trivyignore.yaml" >&2
    exit 1
fi
echo
echo "no serious finding with a fix available in any of the ${#services[@]} images"
