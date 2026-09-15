#!/usr/bin/env bash
#
# Kills the ledger while payments are being captured, brings it back, and fails unless no payment
# was lost. MIZ-90, ADR 0055.
#
#   docker compose up -d --wait
#   ./scripts/chaos-ledger.sh
#
# Payments are taken through the gateway at the steady profile's rate (ADR 0054), with its latency
# and failure thresholds off: requests failing while the ledger is down is the point, not a fault.
# k6 never retries a capture, so nothing here is finished by a merchant trying again. What finishes
# the interrupted captures, if anything, is the platform.
#
# "No payment lost" is checked against the acquirer, not against the platform's own opinion:
#
#   - every capture on the acquirer's statement is CAPTURED in the platform, with its ledger entry
#   - the platform claims no capture from this run that the acquirer did not make
#   - no capture is left started and unfinished once the sweep has had time
#   - nothing needed a person: the platform recovered on its own
#   - and the books balance
#
# The statement is asked for faithfully, without the disagreements the simulator adds on purpose
# for reconciliation to find (Statements.java), because here a disagreement would be a real loss.

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
host() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

BANK="${MIZAN_BANK:-http://localhost:8086}"
NETWORK="${MIZAN_NETWORK:-mizan_default}"
K6_IMAGE="$(grep -E '^K6_IMAGE=' "$ROOT/.env" | cut -d= -f2)"
KILL_AFTER="${MIZAN_CHAOS_KILL_AFTER:-40}"
DOWN_FOR="${MIZAN_CHAOS_DOWN_FOR:-40}"
results="$ROOT/build/chaos"
mkdir -p "$results"

compose() { docker compose -f "$ROOT/docker-compose.yml" "$@"; }
sql() { compose exec -T postgres psql -U "${POSTGRES_USER:-mizan}" -d payment -At -c "$1"; }

as_me=()
if [ "$(uname -s)" = "Linux" ]; then
    as_me=(--user "$(id -u):$(id -g)")
fi

# ---------------------------------------------------------------------------------------------
step "The platform is up"

call 200 GET "$GATEWAY/actuator/health" > /dev/null
[ -n "$K6_IMAGE" ] || fail "no K6_IMAGE in .env"
[ -f "$ROOT/deploy/load/payments.js" ] || fail "no deploy/load/payments.js (MIZ-89)"
started="$(sql "select to_char(now() at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")"
pass "taking payments from $started"

# ---------------------------------------------------------------------------------------------
step "Payments are taken while the ledger dies and comes back"

MSYS_NO_PATHCONV=1 docker run --rm "${as_me[@]}" --network "$NETWORK" \
    -v "$(host "$ROOT/deploy/load"):/scripts:ro" \
    -v "$(host "$results"):/results" \
    "$K6_IMAGE" run --quiet --no-thresholds \
    -e PROFILE=steady -e GATEWAY=http://gateway:8080 -e MERCHANTS=10 \
    /scripts/payments.js > "$results/k6.log" 2>&1 &
load=$!

sleep "$KILL_AFTER"
compose kill ledger-service > /dev/null 2>&1
pass "ledger-service killed ${KILL_AFTER}s into the load"

sleep "$DOWN_FOR"
compose start ledger-service > /dev/null 2>&1
for attempt in $(seq 1 60); do
    compose ps ledger-service --format '{{.Status}}' | grep -q "(healthy)" && break
    sleep 2
done
compose ps ledger-service --format '{{.Status}}' | grep -q "(healthy)" \
    || fail "ledger-service did not come back healthy"
pass "ledger-service back after ${DOWN_FOR}s down"

wait "$load" || true
[ -f "$results/steady.json" ] || fail "k6 wrote no results (see build/chaos/k6.log)"
note "$(grep -vE 'level=warning' "$results/k6.log" | grep -E 'requests|captured|errors' | head -3 | sed 's/^ *//' | tr '\n' ' ')"
failed_during="$(grep -c 'failed capture' "$results/k6.log" || true)"
note "captures that failed while the ledger was down: $failed_during"

# ---------------------------------------------------------------------------------------------
step "Every capture the ledger interrupted is finished by the platform"

unfinished="select count(*) from payment
            where status = 'AUTHORIZED' and capture_started_at is not null
              and needs_attention_since is null"
interrupted="$(sql "$unfinished")"
note "captures started and not finished when the load stopped: $interrupted"

# The sweep waits ten seconds before touching a capture and runs every fifteen.
for attempt in $(seq 1 36); do
    left="$(sql "$unfinished")"
    [ "$left" = "0" ] && break
    sleep 5
done
[ "$left" = "0" ] || fail "$left capture(s) still started and unfinished after three minutes"
pass "no capture is left started and unfinished (there were $interrupted when the load stopped)"

needing="$(sql "select count(*) from payment where needs_attention_since >= '$started'")"
[ "$needing" = "0" ] || fail "$needing payment(s) from this run need a person"
pass "nothing needed a person: the platform finished them on its own"

# ---------------------------------------------------------------------------------------------
step "No payment was lost, by the acquirer's own account"

today="$(TZ=Europe/Istanbul date +%F)"
yesterday="$(TZ=Europe/Istanbul date -d yesterday +%F 2>/dev/null || TZ=Europe/Istanbul date -v-1d +%F)"
: > "$results/acquirer-captured.txt"
for day in "$yesterday" "$today"; do
    curl -fsS "$BANK/statements/$day?currency=TRY&faithful=true" \
        | awk -F'|' '$1 == "D" { print $2 }' >> "$results/acquirer-captured.txt"
done
sort -u -o "$results/acquirer-captured.txt" "$results/acquirer-captured.txt"

sql "select acquirer_reference from payment where status = 'CAPTURED' and acquirer_reference is not null" \
    | sort -u > "$results/platform-captured.txt"

missing="$(comm -23 "$results/acquirer-captured.txt" "$results/platform-captured.txt")"
[ -z "$missing" ] || {
    printf '%s\n' "$missing" > "$results/lost.txt"
    fail "$(printf '%s\n' "$missing" | wc -l | tr -d ' ') capture(s) the acquirer made are not captured in the platform (build/chaos/lost.txt)"
}
pass "all $(wc -l < "$results/acquirer-captured.txt" | tr -d ' ') captures on the acquirer's statement are captured in the platform"

sql "select acquirer_reference from payment where status = 'CAPTURED' and updated_at >= '$started'" \
    | sort -u > "$results/platform-captured-this-run.txt"
invented="$(comm -13 "$results/acquirer-captured.txt" "$results/platform-captured-this-run.txt")"
[ -z "$invented" ] || fail "the platform claims captures from this run the acquirer did not make: $invented"
pass "and the platform claims no capture from this run that the acquirer did not make"

without_entry="$(sql "select count(*) from payment where status = 'CAPTURED' and ledger_entry_id is null")"
[ "$without_entry" = "0" ] || fail "$without_entry captured payment(s) have no ledger entry"
pass "every captured payment points at its ledger entry"

# ---------------------------------------------------------------------------------------------
"$(dirname "$0")/books-balance.sh"

pass "the ledger died mid-capture under load, and no payment was lost"
