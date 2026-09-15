#!/usr/bin/env bash
#
# Cuts Kafka off while payments are taken, brings it back, and fails unless every event still
# reaches the service that consumes it. MIZ-91.
#
#   docker compose up -d --wait
#   ./scripts/chaos-kafka.sh
#
# Kafka is paused rather than stopped. A paused broker accepts no traffic and refuses none either:
# connections hang, which is what a network partition looks like from the producer's side and is
# harder on a client than a clean refusal. Nothing else is touched, so payment-service keeps its
# database and payments can still be taken.
#
# What must hold:
#
#   - payments are still accepted while the broker is unreachable
#   - events wait in the outbox, and the backlog is visible in Prometheus while they do
#   - once the broker is back, the outbox drains to nothing
#   - every event written during the run was published, and notification-service handled each
#     one exactly once (it deduplicates on the event id, so a repeat is recorded once)
#   - and the books balance
#
# It also measures how long the relay is stuck on each attempt while the broker hangs, which is the
# question raised on MIZ-91 about Kafka's 60 second max.block.ms default.

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
host() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

NETWORK="${MIZAN_NETWORK:-mizan_default}"
K6_IMAGE="$(grep -E '^K6_IMAGE=' "$ROOT/.env" | cut -d= -f2)"
PAUSE_AFTER="${MIZAN_CHAOS_PAUSE_AFTER:-40}"
PAUSED_FOR="${MIZAN_CHAOS_PAUSED_FOR:-60}"
DRAIN_WITHIN="${MIZAN_CHAOS_DRAIN_WITHIN:-300}"
results="$ROOT/build/chaos-kafka"
mkdir -p "$results"

compose() { docker compose -f "$ROOT/docker-compose.yml" "$@"; }
payments_sql() { compose exec -T postgres psql -U "${POSTGRES_USER:-mizan}" -d payment -At -c "$1"; }
notifications_sql() { compose exec -T postgres psql -U "${POSTGRES_USER:-mizan}" -d notification -At -c "$1"; }
waiting() { payments_sql "select count(*) from outbox_event where published_at is null"; }

as_me=()
if [ "$(uname -s)" = "Linux" ]; then
    as_me=(--user "$(id -u):$(id -g)")
fi

# Whatever happens, the broker is not left frozen for the next thing that uses this stack.
trap 'compose unpause kafka > /dev/null 2>&1 || true' EXIT

# ---------------------------------------------------------------------------------------------
step "The platform is up and nothing is waiting to leave"

call 200 GET "$GATEWAY/actuator/health" > /dev/null
[ -n "$K6_IMAGE" ] || fail "no K6_IMAGE in .env"
[ "$(waiting)" = "0" ] || fail "the outbox already has $(waiting) unpublished event(s); start from a drained one"
started="$(payments_sql "select to_char(now() at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")"
pass "taking payments from $started with an empty outbox"

# ---------------------------------------------------------------------------------------------
step "Payments are taken while Kafka is unreachable"

MSYS_NO_PATHCONV=1 docker run --rm "${as_me[@]}" --network "$NETWORK" \
    -v "$(host "$ROOT/deploy/load"):/scripts:ro" \
    -v "$(host "$results"):/results" \
    "$K6_IMAGE" run --quiet --no-thresholds \
    -e PROFILE=steady -e GATEWAY=http://gateway:8080 -e MERCHANTS=10 \
    ${MIZAN_LOAD_RATE:+-e RATE="$MIZAN_LOAD_RATE"} \
    /scripts/payments.js > "$results/k6.log" 2>&1 &
load=$!

sleep "$PAUSE_AFTER"
compose pause kafka > /dev/null
paused_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
pass "kafka paused ${PAUSE_AFTER}s into the load"

peak=0
for second in $(seq 5 5 "$PAUSED_FOR"); do
    sleep 5
    now="$(waiting)"
    [ "$now" -gt "$peak" ] && peak="$now"
done
[ "$peak" -gt 0 ] || fail "nothing waited in the outbox while the broker was unreachable, so this tested nothing"
pass "events waited in the outbox while it was paused: up to $peak"

compose unpause kafka > /dev/null
resumed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
pass "kafka back after ${PAUSED_FOR}s"

wait "$load" || true
[ -f "$results/steady.json" ] || fail "k6 wrote no results (see build/chaos-kafka/k6.log)"
note "$(grep -vE 'level=warning' "$results/k6.log" | grep -E 'requests|captured' | head -2 | sed 's/^ *//' | tr '\n' ' ')"
failed="$(grep -c 'failed ' "$results/k6.log" || true)"
note "payment requests that failed during the run: $failed"

# ---------------------------------------------------------------------------------------------
step "The relay, while the broker hung"

compose logs --no-log-prefix --since "$paused_at" --until "$resumed_at" payment-service 2>/dev/null \
    | grep 'could not publish' > "$results/publish-failures.log" || true
attempts="$(wc -l < "$results/publish-failures.log" | tr -d ' ')"
note "failed publish attempts logged while paused: $attempts"
if [ "$attempts" -ge 2 ]; then
    # Seconds between consecutive failures: how long each attempt held the relay before giving up.
    gaps="$(grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+' "$results/publish-failures.log" \
        | "$PYTHON" -c '
import sys
from datetime import datetime
times = [datetime.fromisoformat(line.strip()[:26]) for line in sys.stdin if line.strip()]
gaps = [(b - a).total_seconds() for a, b in zip(times, times[1:])]
print("longest %.1fs, median %.1fs" % (max(gaps), sorted(gaps)[len(gaps) // 2]) if gaps else "none")
')"
    note "time between failed attempts: $gaps"
fi

# ---------------------------------------------------------------------------------------------
step "The outbox drains, and the backlog was visible while it waited"

for attempt in $(seq 1 $((DRAIN_WITHIN / 5))); do
    left="$(waiting)"
    [ "$left" = "0" ] && break
    sleep 5
done
[ "$left" = "0" ] || fail "$left event(s) still unpublished ${DRAIN_WITHIN}s after the broker came back"
drained_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
pass "the outbox is empty again (drained by $drained_at)"

seen="$(curl -fsS -G "$PROMETHEUS/api/v1/query" \
    --data-urlencode "query=max_over_time(mizan_outbox_waiting[10m])" \
    | "$PYTHON" -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else -1)')"
[ "$seen" -gt 0 ] || fail "Prometheus never saw a backlog (max over 10m: $seen), so nobody would have been told"
pass "Prometheus saw the backlog: mizan_outbox_waiting reached $seen"

# ---------------------------------------------------------------------------------------------
step "Every event reached the service that consumes it, once"

payments_sql "select id from outbox_event where occurred_at >= '$started' order by id" > "$results/written.txt"
unpublished="$(payments_sql "select count(*) from outbox_event where occurred_at >= '$started' and published_at is null")"
[ "$unpublished" = "0" ] || fail "$unpublished event(s) from this run were never published"
pass "all $(wc -l < "$results/written.txt" | tr -d ' ') events written during the run were published"

# Consumers catch up after the relay does.
for attempt in $(seq 1 36); do
    notifications_sql "select event_id from handled_event where handler = 'payment-notifications' and handled_at >= '$started' order by event_id" \
        > "$results/handled.txt"
    missing="$(comm -23 <(sort "$results/written.txt") <(sort "$results/handled.txt") | wc -l | tr -d ' ')"
    [ "$missing" = "0" ] && break
    sleep 5
done
[ "$missing" = "0" ] || fail "$missing event(s) were published but notification-service never handled them"
pass "notification-service handled every one of them"

twice="$(notifications_sql "select count(*) from (select event_id from handled_event where handler = 'payment-notifications' group by event_id having count(*) > 1) repeated")"
[ "$twice" = "0" ] || fail "$twice event(s) were handled more than once"
pass "and none of them twice"

# ---------------------------------------------------------------------------------------------
"$(dirname "$0")/books-balance.sh"

pass "Kafka was cut off under load, payments kept being taken, and every event arrived once"
