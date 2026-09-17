#!/usr/bin/env bash
#
# Walks the whole platform the way a merchant would, against the running Compose stack, and
# exits non zero the moment anything is not as it should be.
#
# This is the check the test suite structurally cannot do. Every service here is a real
# process talking to real Postgres over a real network, started from an image built the way
# it is deployed, and reached through the gateway rather than through MockMvc. Three defects
# in this platform's history were only ever visible from here: a service that would not start
# because its runtime image lacked a module the tests had, a route the gateway did not
# forward, and an idempotency mechanism that was quietly inactive while the suite stayed
# green.
#
# Usage:  docker compose up -d --wait  &&  ./scripts/smoke.sh

DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/mizan.sh
. "$DIRECTORY/mizan.sh"

# A run of its own each time. The platform refuses a merchant whose email is taken and a
# payment whose reference is reused, and both refusals are correct, so a script that reran
# into them would be reporting its own laziness as a failure.
# What the books say the platform owes this merchant, which is not the same as what a
# settlement batch came to once money has gone back to a customer.
settlementBalance() {
    authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/accounts" | "$PYTHON" -c '
import json, sys
print(next(a["balance"] for a in json.load(sys.stdin) if a["code"] == "settlement.try"))'
}

RUN="$(date +%s)-$RANDOM"
EMAIL="smoke-$RUN@mizan.local"
PASSWORD="correct-horse-battery-staple"
AMOUNT=125000

printf '%sMizan smoke check%s  %s%s%s\n' "$BOLD" "$OFF" "$DIM" "$GATEWAY" "$OFF"

# ---------------------------------------------------------------------------------------
step "1. The platform is up"

health=$(call 200 GET "$GATEWAY/actuator/health")
[ "$(printf '%s' "$health" | field status)" = "UP" ] || fail "the gateway is not healthy"
pass "the gateway answers, and is the only door: everything below goes through it"

# ---------------------------------------------------------------------------------------
step "2. A merchant registers and signs in"

registered=$(call 201 POST "$GATEWAY/api/v1/merchants" \
    "{\"merchantName\":\"Smoke Test Co\",\"fullName\":\"Ada Lovelace\",
      \"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
    -H "Idempotency-Key: $(key)")

MERCHANT=$(printf '%s' "$registered" | field merchant.id)
pass "registered merchant $MERCHANT"

tokens=$(call 200 POST "$GATEWAY/api/v1/tokens" \
    "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
AUTH=$(printf '%s' "$tokens" | field accessToken)
pass "signed in and holding an access token"

call 401 GET "$GATEWAY/api/v1/merchants/$MERCHANT/payments" > /dev/null
pass "and without one, the same read is refused"

# ---------------------------------------------------------------------------------------
step "3. The merchant opens the account their money will be owed into"

authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/accounts" \
    '{"code":"settlement.try","name":"Owed to the merchant, TRY",
      "type":"LIABILITY","currency":"TRY"}' > /dev/null
pass "opened settlement.try, because the ledger does not open accounts on anyone's behalf"

# ---------------------------------------------------------------------------------------
step "4. A payment is created, authorized and captured"

payment=$(authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments" \
    "{\"amount\":$AMOUNT,\"currency\":\"TRY\",\"reference\":\"order-$RUN\"}")
PAYMENT=$(printf '%s' "$payment" | field id)
[ "$(printf '%s' "$payment" | field status)" = "CREATED" ] || fail "a new payment is not CREATED"
pass "created payment $PAYMENT, which has contacted nobody and moved nothing"

authorized=$(authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/authorize" \
    '{"card":"4000000000000000"}')
[ "$(printf '%s' "$authorized" | field status)" = "AUTHORIZED" ] || fail "the payment was not authorized"
[ -n "$(printf '%s' "$authorized" | field acquirerReference)" ] || fail "no acquirer reference"
[ "$(printf '%s' "$authorized" | field cardLastFour)" = "0000" ] || fail "the card was not reduced to four digits"
pass "authorized, keeping the acquirer's reference and four digits of the card"

entries=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/entries")
[ "$(printf '%s' "$entries" | count)" = "0" ] || fail "an authorization posted to the books"
pass "and the books are untouched: a promise that money is there is not a movement of it"

captured=$(authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/capture")
[ "$(printf '%s' "$captured" | field status)" = "CAPTURED" ] || fail "the payment was not captured"
ENTRY=$(printf '%s' "$captured" | field ledgerEntryId)
pass "captured, and pointing at entry $ENTRY"

# ---------------------------------------------------------------------------------------
step "5. The entry is really in the books, and says what a capture means"

entry=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/entries/$ENTRY")
postings=$(printf '%s' "$entry" | "$PYTHON" -c '
import json, sys
entry = json.load(sys.stdin)
for posting in sorted(entry["postings"], key=lambda p: p["accountCode"]):
    print(posting["accountCode"], posting["amount"], posting["direction"])
')
printf '%s\n' "$postings" | while read -r line; do note "$line"; done

printf '%s' "$postings" | grep -q "platform.clearing.try $AMOUNT DEBIT" \
    || fail "the platform's clearing account was not debited"
printf '%s' "$postings" | grep -q "settlement.try -$AMOUNT CREDIT" \
    || fail "the merchant's settlement account was not credited"
pass "the platform holds more at the acquirer, and owes the merchant more"

# ---------------------------------------------------------------------------------------
step "6. What must not be allowed, is not"

authed 422 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/capture" > /dev/null
pass "a captured payment cannot be captured again"
authed 422 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/void" > /dev/null
pass "nor voided, because releasing money already taken is a refund and not a void"

# A merchant reaching the route that crosses into the platform's books. It is not routed
# from the edge at all, which is the answer this asserts; the ledger would refuse it anyway.
call 404 POST "$GATEWAY/internal/ledger-service/internal/entries" '{}' \
    -H "Authorization: Bearer $AUTH" > /dev/null
pass "and the route that can move money between two merchants' books is not reachable here"

# The review queue, reached the way an analyst would. Nothing is held on a clean run, so what
# this asserts is the thing a test suite structurally cannot: that the gateway forwards the
# route at all. A queue nobody can reach is a queue of customers waiting for nothing.
queue=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/reviews")
[ "$queue" = "[]" ] || fail "a clean run should have held nothing, and this queue holds: $queue"
pass "the review queue answers through the gateway, and has nothing waiting"

# Paging, which is only observable from outside: a body that is still a plain array, and the
# answer's shape in headers. An envelope here would have broken every client parsing a list.
listing=$(curl -s -D - -H "Authorization: Bearer $AUTH"     "$GATEWAY/api/v1/merchants/$MERCHANT/payments?size=1")
printf '%s' "$listing" | grep -qi "^x-total-count:" || fail "no count came back: $listing"
printf '%s' "$listing" | grep -q "^\[" || fail "the body is no longer a plain list"
authed 422 GET "$GATEWAY/api/v1/merchants/$MERCHANT/payments?status=NEARLY" > /dev/null
pass "payments page, say how many there are, and refuse a filter nobody understands"

# Risk answers services, not merchants. None of its paths names a merchant the gateway could
# scope by, so a route from the edge would have been a way to read another merchant's rulings
# and to score against their baseline, with nothing but a token of your own.
authed 404 GET "$GATEWAY/api/v1/risk/rulings/merchants/$MERCHANT" > /dev/null
authed 404 POST "$GATEWAY/api/v1/risk/scores" '{}' > /dev/null
pass "and risk is not reachable from the edge at all, by anybody"

# The books page the same way payments do, and narrow to one account. Only visible from
# outside: the body stays a plain array and the shape of the answer is in headers.
books=$(curl -s -D - -H "Authorization: Bearer $AUTH"     "$GATEWAY/api/v1/merchants/$MERCHANT/entries?size=1")
printf '%s' "$books" | grep -qi "^x-total-count:" || fail "the entries do not say how many: $books"
authed 422 GET "$GATEWAY/api/v1/merchants/$MERCHANT/entries?page=900&size=50" > /dev/null
pass "the entries page too, and refuse to be read ten thousand deep"

# How business is, counted by the database rather than by whoever asked. An intent nobody
# ever authorized is the figure a naive count gets wrong, so one is created on purpose: it
# must show up as created and not as attempted.
authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments"     "{\"amount\":1000,\"currency\":\"TRY\",\"reference\":\"order-$RUN-intent\"}" > /dev/null

overview=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/summary?zone=Europe/Istanbul")
created=$(printf '%s' "$overview" | field totals.created)
attempted=$(printf '%s' "$overview" | field totals.attempted)
[ "$(printf '%s' "$overview" | field totals.captured)" != "0" ] || fail "nothing was captured?"
[ "$created" -gt "$attempted" ]     || fail "an intent nobody authorized is being counted as an attempt ($created, $attempted)"
authed 422 GET "$GATEWAY/api/v1/merchants/$MERCHANT/summary?zone=Middle/Earth" > /dev/null
pass "the overview counts what happened, and refuses a time zone nobody lives in"

# ---------------------------------------------------------------------------------------
step "7. A second payment is authorized and voided"

second=$(authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments" \
    "{\"amount\":50000,\"currency\":\"TRY\",\"reference\":\"order-$RUN-cancelled\"}")
SECOND=$(printf '%s' "$second" | field id)
authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$SECOND/authorize" \
    '{"card":"4000000000000000"}' > /dev/null

voided=$(authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$SECOND/void" \
    '{"reason":"the customer cancelled the order"}')
[ "$(printf '%s' "$voided" | field status)" = "VOIDED" ] || fail "the payment was not voided"
pass "voided payment $SECOND"

after=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/entries")
[ "$(printf '%s' "$after" | count)" = "1" ] || fail "a void wrote to the books"
pass "and the books still hold one entry: no money moved, so there is nothing to record"

# ---------------------------------------------------------------------------------------
step "8. A declined payment keeps the acquirer's reason"

declined=$(authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments" \
    "{\"amount\":$AMOUNT,\"currency\":\"TRY\",\"reference\":\"order-$RUN-declined\"}")
DECLINED=$(printf '%s' "$declined" | field id)

refused=$(authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$DECLINED/authorize" \
    '{"card":"4000000000000002"}')
[ "$(printf '%s' "$refused" | field status)" = "DECLINED" ] || fail "the payment was not declined"
[ "$(printf '%s' "$refused" | field declineReason)" = "insufficient_funds" ] \
    || fail "the acquirer's reason was not kept"
pass "declined with insufficient_funds, which is what the merchant will be asked about"

# ---------------------------------------------------------------------------------------
step "9. The capture was announced, and somebody else can read it"

# Through the broker rather than through the outbox table, because "we wrote a row" and
# "a consumer can see it" are different claims and only the second one matters to anybody
# else. Skipped rather than failed where there is no Compose stack to ask, since everything
# above this point works against any deployment.
if command -v docker > /dev/null 2>&1 && docker compose ps kafka > /dev/null 2>&1; then
    TOPIC_DUMP="$(mktemp)"

    # The relay publishes on a timer, so this may need asking more than once.
    #
    # MSYS_NO_PATHCONV stops Git Bash on Windows rewriting the path inside the container
    # into a Windows one, which it does silently and which then fails as "no such file".
    # It means nothing anywhere else.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        MSYS_NO_PATHCONV=1 docker compose exec -T kafka \
            /opt/kafka/bin/kafka-console-consumer.sh \
            --bootstrap-server localhost:9092 \
            --topic mizan.payment.events \
            --from-beginning --timeout-ms 4000 \
            2>/dev/null > "$TOPIC_DUMP" || true

        if grep -q "$PAYMENT" "$TOPIC_DUMP" 2>/dev/null; then
            break
        fi
    done

    grep "$PAYMENT" "$TOPIC_DUMP" | grep -q '"type":"payment.captured"' \
        || fail "the capture of $PAYMENT never reached mizan.payment.events"
    grep -q "$ENTRY" "$TOPIC_DUMP" \
        || fail "the event does not say where in the books the money landed"
    pass "payment.captured reached mizan.payment.events, carrying the ledger entry"

    # The one thing an event must never carry, on a topic several services and a broker's
    # disk will hold.
    if grep -q "4000000000000000" "$TOPIC_DUMP"; then
        fail "a card number is on the topic"
    fi
    pass "and no card number is on it, nor could be: this service does not keep one"
    rm -f "$TOPIC_DUMP"
else
    note "no Compose stack to ask, so the broker was not checked"
fi

# ---------------------------------------------------------------------------------------
step "10. Another service heard it, and decided what to tell the merchant"

# The consuming end. Events are delivered at least once by design, so what is checked is not
# only that something arrived but that exactly one thing did.
told=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    told=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/notifications")
    if [ "$(printf '%s' "$told" | count)" != "0" ]; then
        break
    fi
    sleep 1
done

[ "$(printf '%s' "$told" | count)" != "0" ] || fail "nothing was ever said about these payments"
printf '%s' "$told" | "$PYTHON" -c '
import sys, json
for notification in json.load(sys.stdin):
    print("  ", notification["kind"], "|", notification["message"])
'

captured_count=$(printf '%s' "$told" | "$PYTHON" -c '
import sys, json
print(sum(1 for n in json.load(sys.stdin) if n["kind"] == "PAYMENT_CAPTURED"))
')
[ "$captured_count" = "1" ] \
    || fail "one capture should produce one notification, and produced $captured_count"
pass "one capture, one notification, however many times the event was delivered"

# ---------------------------------------------------------------------------------------
step "11. The money can be given back, and never more than was taken"

# The step MIZ-26 could not have: it asked for a refund here and refunds did not exist. This
# is what it was asking for.
refund=$(authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/refunds" \
    "{\"amount\":25000,\"reference\":\"return-$RUN\",\"reason\":\"the customer sent one back\"}")
refund_entry=$(printf '%s' "$refund" | field ledgerEntryId)
pass "refunded 250.00 of 1250.00, recorded as entry $refund_entry"

corrects=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/entries/$refund_entry" | field corrects)
[ "$corrects" = "$ENTRY" ] \
    || fail "the refund entry names $corrects rather than the capture $ENTRY"
pass "and it names the capture it reverses, so both stay readable and neither is edited"

# The whole point of the arithmetic: what is left is what is left.
authed 422 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/refunds" \
    "{\"amount\":100001,\"reference\":\"too-much-$RUN\"}" > /dev/null
pass "and one more minor unit than remains is refused"

# ---------------------------------------------------------------------------------------
step "12. Nothing is stuck"

# The one place that answers "what needs a person". Asserted here so that a regression which
# starts stranding payments fails a pull request rather than being noticed next quarter.
if command -v docker > /dev/null 2>&1 && docker compose ps payment-service > /dev/null 2>&1; then
    stuck=$(call 200 GET "$PAYMENTS/actuator/stuck")
    total=$(printf '%s' "$stuck" | field total)

    if [ "$total" != "0" ]; then
        printf '%s' "$stuck" | "$PYTHON" -c '
import sys, json
for one in json.load(sys.stdin)["stuck"]:
    print("  ", one["kind"], one["id"], "|", one["reason"])
' >&2
        fail "$total thing(s) need a person, and a clean run should leave none"
    fi
    pass "nothing needs a person, which is what a clean run should leave"
else
    note "no Compose stack to ask, so this was not checked"
fi

# ---------------------------------------------------------------------------------------
step "13. A service that is down is still answered in the platform's own shape"

# Only visible with real processes: nothing in a test suite can stop a container. What is
# being checked is that a caller who cannot be served is told so in the same error shape as
# every refusal, with a code they can branch on and a correlation id they can quote --
# because "this failed, try again" and "this was refused, do not" is the one distinction a
# client most needs at exactly the moment the platform is least able to make it.
if command -v docker > /dev/null 2>&1 && docker compose ps notification-service > /dev/null 2>&1; then
    docker compose stop notification-service > /dev/null 2>&1
    note "notification-service stopped on purpose"

    down=$(authed 503 GET "$GATEWAY/api/v1/merchants/$MERCHANT/notifications")
    code=$(printf '%s' "$down" | field code)
    [ "$code" = "UPSTREAM_UNAVAILABLE" ] || fail "answered with $code, not UPSTREAM_UNAVAILABLE"
    [ -n "$(printf '%s' "$down" | field correlationId)" ] || fail "no correlation id to quote"
    pass "a problem detail carrying UPSTREAM_UNAVAILABLE and a correlation id"

    # And nothing about the platform in it. A merchant cannot act on a host and a port, and
    # somebody probing this should not be handed the shape of what is behind the gateway.
    case "$down" in
        *notification-service:*|*Connection*|*Exception*|*requestId*)
            fail "the answer leaks something internal: $down" ;;
    esac
    pass "and it says nothing about what is behind the gateway"

    docker compose up -d --wait notification-service > /dev/null 2>&1
    note "notification-service started again"
else
    note "no Compose stack to stop, so this was not checked"
fi

# ---------------------------------------------------------------------------------------
step "14. A browser can hold a session without holding a secret"

# The console keeps its refresh token in a cookie it cannot read, which is only true if the
# platform actually sets one with the right flags. A unit test can assert the header; only
# this can assert that it survives the gateway, which is the hop where a Set-Cookie is most
# easily lost.
JAR="$(mktemp)"
browser=$(curl -s -c "$JAR" -X POST "$GATEWAY/api/v1/tokens"     -H 'Content-Type: application/json'     -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
[ -n "$(printf '%s' "$browser" | field accessToken)" ] || fail "signing in did not answer"

grep -q "mizan_refresh" "$JAR" || fail "no session cookie survived the gateway"
grep -qi "^#HttpOnly_" "$JAR" || fail "the session cookie is readable by script"
grep -q "/api/v1/tokens" "$JAR" || fail "the session cookie is not scoped to the token routes"
pass "the refresh token came back HttpOnly and scoped to /api/v1/tokens"

renewed=$(curl -s -b "$JAR" -c "$JAR" -X POST "$GATEWAY/api/v1/tokens/refresh")
[ -n "$(printf '%s' "$renewed" | field accessToken)" ] || fail "the cookie did not renew: $renewed"
pass "and renews the session with no body at all, which is what a reload is"

curl -s -o /dev/null -b "$JAR" -c "$JAR" -X POST "$GATEWAY/api/v1/tokens/sign-out"
after=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$GATEWAY/api/v1/tokens/refresh")
[ "$after" = "401" ] || fail "the refresh token still works after signing out ($after)"
rm -f "$JAR"
pass "signing out revokes it rather than only forgetting it here"

# ---------------------------------------------------------------------------------------
step "15. A day of captures becomes a batch, and the fee adds up twice"

# Only visible from here: the capture crosses a topic to another service, which groups it,
# charges for it and has to agree with itself about the fee. A unit test can check the
# arithmetic; nothing but this can check that the event carried what the arithmetic needs.
if command -v docker > /dev/null 2>&1 && docker compose ps settlement-service > /dev/null 2>&1; then
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        waiting=$(call 200 GET "$SETTLEMENT/actuator/settlements")
        heard=$(printf '%s' "$waiting" | "$PYTHON" -c '
import json, sys
print(sum(w["payments"] for w in json.load(sys.stdin)["waiting"]))')
        [ "$heard" != "0" ] && break
        sleep 2
    done
    [ "$heard" != "0" ] || fail "settlement never heard about the capture"

    today=$("$PYTHON" -c '
import datetime, zoneinfo
print(datetime.datetime.now(zoneinfo.ZoneInfo("Europe/Istanbul")).date())')
    call 200 POST "$SETTLEMENT/actuator/settlements/$today" '{}' > /dev/null

    batches=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/settlements")
    captured=$(printf '%s' "$batches" | field batches.0.captured)
    fee=$(printf '%s' "$batches" | field batches.0.fee)
    net=$(printf '%s' "$batches" | field batches.0.net)
    [ "$((captured - fee))" = "$net" ] || fail "$captured minus $fee is not $net"
    pass "captured $captured, charged $fee, owed $net"

    # The property the fee arithmetic exists for: a merchant adding up their own statement
    # gets the number they were charged.
    batch=$(printf '%s' "$batches" | field batches.0.id)
    items=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/settlements/$batch/items")
    attributed=$(printf '%s' "$items" | "$PYTHON" -c '
import json, sys
print(sum(i["fee"] for i in json.load(sys.stdin)))')
    [ "$attributed" = "$fee" ]         || fail "the payments come to $attributed but the batch was charged $fee"
    pass "and the fees on its payments come to exactly that"

    # Repeatable, which is what makes it safe to hand to a person during an incident.
    call 200 POST "$SETTLEMENT/actuator/settlements/$today" '{}' > /dev/null
    howMany=$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/settlements" | "$PYTHON" -c '
import json, sys
print(len(json.load(sys.stdin)["batches"]))')
    [ "$howMany" = "1" ] || fail "closing twice made $howMany batches"
    pass "closing the same day again answers with the same batch"

    # The fee reached the platform's own account, so this merchant is owed the fee less than
    # the books said before. Revenue that is not written down is revenue nobody can reconcile.
    owed=$(settlementBalance)
    [ "$owed" != "0" ] || fail "the fee seems to have taken everything that was owed"
    pass "and the fee left the merchant's balance: $owed is owed now"

    # This merchant refunded part of that day after it was captured, which is exactly the case
    # the payout guard exists for. The batch still says what the day's captures came to —
    # refunds are not netted in — and what is owed has moved, so paying the batch in full
    # would be paying them money that has already gone back to a customer.
    refused=$(call 422 POST "$SETTLEMENT/actuator/payouts/$batch" '{}')
    printf '%s' "$refused" | grep -q "is owed to this merchant"         || fail "the payout was not refused for the right reason: $refused"
    [ "$(settlementBalance)" = "$owed" ] || fail "a refused payout moved the balance"
    pass "and paying it is refused, because a refund has moved what is owed"
else
    note "no Compose stack to settle, so this was not checked"
fi

# ---------------------------------------------------------------------------------------
step "16. What the bank says it settled is compared with what this platform believes"

# Only visible from here. The statement is somebody else's file, fetched over HTTP from a
# process that was never told what this platform thinks happened — and the bank simulator
# disagrees on purpose, three ways. A suite can check the comparison; nothing but this can
# check that the comparison is being made against a real file from a real other service.
if command -v docker > /dev/null 2>&1 && docker compose ps settlement-service > /dev/null 2>&1; then
    day=$("$PYTHON" -c '
import datetime, zoneinfo
print(datetime.datetime.now(zoneinfo.ZoneInfo("Europe/Istanbul")).date())')

    found=$(call 200 POST "$SETTLEMENT/actuator/reconciliation/$day" '{}')
    matched=$(printf '%s' "$found" | field matched)
    missing=$(printf '%s' "$found" | field missingFromStatement)
    extra=$(printf '%s' "$found" | field extraOnStatement)
    differing=$(printf '%s' "$found" | field amountsDiffer)

    # The phantom is invented out of nothing, so it is there on every day of every run, and
    # it is asserted by name: an extra that turned up for some other reason would pass a
    # count and prove nothing.
    phantom="acq_never_issued_$(printf '%s' "$day" | tr -d '-')"
    [ "$extra" -ge 1 ] || fail "the phantom transaction was not noticed: $found"

    # And the two sides must part company somewhere on this side too. Which of the two it is
    # depends on how much the acquirer settled that day and is not asserted here — the
    # simulator only drops a transaction when it has two to choose from, and a fresh stack
    # may not. Both kinds are covered by ReconciliationTest against data it controls.
    [ "$((missing + differing))" -ge 1 ]         || fail "the statement and this platform agreed completely, which cannot be: $found"
    pass "$matched matched, $missing missing, $extra extra, $differing differing"

    # Named differences rather than a count, and both figures on the one that disagrees, so
    # the person who picks this up is told what to go and ask about.
    outstanding=$(call 200 GET "$SETTLEMENT/actuator/reconciliation")
    printf '%s' "$outstanding" | grep -q "$phantom"         || fail "the extra is not the phantom the bank invented: $outstanding"
    both=$(printf '%s' "$outstanding" | "$PYTHON" -c '
import json, sys
rows = json.load(sys.stdin)["differences"]
differing = [r for r in rows if r["outcome"] == "AMOUNTS_DIFFER"]
extra = [r for r in rows if r["outcome"] == "EXTRA_ON_STATEMENT"]
print(len(extra) > 0
      and all(r["statement_amount"] is not None for r in extra)
      and all(r["platform_amount"] is not None and r["statement_amount"] is not None
              for r in differing))')
    [ "$both" = "True" ] || fail "a difference does not say the figures it has: $outstanding"
    pass "and each difference says what this platform has and what the bank says"

    # The only time anybody reconciles twice is after an incident, which is the worst moment
    # to be handed the same page of problems again as if they were all new.
    before=$(printf '%s' "$outstanding" | "$PYTHON" -c '
import json, sys; print(len(json.load(sys.stdin)["differences"]))')
    again=$(call 200 POST "$SETTLEMENT/actuator/reconciliation/$day" '{}')
    for figure in matched missingFromStatement extraOnStatement amountsDiffer; do
        [ "$(printf '%s' "$again" | field $figure)" = "$(printf '%s' "$found" | field $figure)" ]             || fail "$figure changed between two runs of the same day"
    done
    after=$(call 200 GET "$SETTLEMENT/actuator/reconciliation" | "$PYTHON" -c '
import json, sys; print(len(json.load(sys.stdin)["differences"]))')
    [ "$before" = "$after" ] || fail "a second run reported $after differences, not $before"
    pass "reconciling the day again says the same thing, and not twice as many problems"

    # Nothing above adjusted anything. A job that could quietly make the books agree with the
    # bank is a job that could quietly make them wrong, so the books must be untouched.
    [ "$(printf '%s' "$(call 200 GET "$LEDGER/actuator/ledgerintegrity")" | field sound)"         != "False" ] || fail "reconciliation moved money"
    pass "and it changed nothing: a difference is reported, never written off"
else
    note "no Compose stack, so the bank's statement was not read"
fi

# ---------------------------------------------------------------------------------------
step "17. A difference reaches a person, and a person's decision is on the record"

# A reconciliation nobody reads is a reconciliation that was not run. Only visible from here:
# the ruling is checked against the ledger over HTTP, so a correction is refused by a service
# that was never told what settlement believes.
if command -v docker > /dev/null 2>&1 && docker compose ps settlement-service > /dev/null 2>&1; then
    queue=$(call 200 GET "$SETTLEMENT/actuator/reconciliation")
    [ "$(printf '%s' "$queue" | field outstanding)" -ge 1 ]         || fail "nothing is waiting for anybody, which cannot be after step 16: $queue"

    difference=$(printf '%s' "$queue" | "$PYTHON" -c '
import json, sys
print(json.load(sys.stdin)["differences"][0]["id"])')
    ruling="$SETTLEMENT/actuator/reconciliation/differences/$difference"

    # A decision nobody owns and nobody explained is not an audit trail, and the platform says
    # so in those words rather than answering with a sentence about a missing form field.
    anonymous=$(call 422 POST "$ruling" '{"ruling":"ACKNOWLEDGED","ruledBy":"ada@mizan.local"}')
    printf '%s' "$anonymous" | grep -q "not an audit trail"         || fail "a decision with no reason was not refused for the right reason: $anonymous"

    # And there is no writing one off. An automatic write-off is the feature that makes a
    # ledger untrustworthy, and a manual one by another name is the same feature.
    refused=$(call 422 POST "$ruling"         '{"ruling":"WRITTEN_OFF","ruledBy":"ada@mizan.local","why":"it is only a kurus"}')
    printf '%s' "$refused" | grep -q "ACKNOWLEDGED" || fail "a write-off was not refused: $refused"

    # A correction is checked against the books rather than believed.
    invented=$(call 422 POST "$ruling"         "{\"ruling\":\"CORRECTED\",\"ruledBy\":\"ada@mizan.local\",\"why\":\"posted it\",
          \"correctedBy\":\"$(key)\"}")
    printf '%s' "$invented" | grep -q "cannot be found is not a correction"         || fail "a correction naming an entry nobody posted was accepted: $invented"
    pass "a ruling has to be owned, explained, and true, or it is refused"

    decided=$(call 200 POST "$ruling"         '{"ruling":"ACKNOWLEDGED","ruledBy":"ada@mizan.local",
          "why":"the bank invented this one; asked them to withdraw it"}')
    printf '%s' "$decided" | grep -q "ACKNOWLEDGED" || fail "the ruling was not recorded: $decided"

    after=$(call 200 GET "$SETTLEMENT/actuator/reconciliation")
    printf '%s' "$after" | "$PYTHON" -c '
import json, sys
waiting = json.load(sys.stdin)["differences"]
sys.exit(0 if not any(d["id"] == "'"$difference"'" for d in waiting) else 1)'         || fail "a difference somebody has ruled on is still waiting for somebody"
    pass "deciding about it takes it out of the queue, and nothing else does"

    history=$(call 200 GET "$ruling")
    printf '%s' "$history" | grep -q "ada@mizan.local" || fail "the ruling is not on the record"
    printf '%s' "$history" | grep -q "asked them to withdraw it"         || fail "the reason is not on the record: $history"
    pass "and who decided it, and why, is on the record afterwards"

    # The whole point of the two verbs. Acknowledging explains a difference; it never undoes
    # one, and a correction is an entry in the ledger like any other.
    [ "$(printf '%s' "$(call 200 GET "$LEDGER/actuator/ledgerintegrity")" | field sound)"         != "False" ] || fail "ruling on a difference moved money"
    pass "and the books are exactly where they were: a ruling explains, it does not move money"
else
    note "no Compose stack, so nothing was ruled on"
fi

# ---------------------------------------------------------------------------------------
# ---------------------------------------------------------------------------------------
step "18. The platform's own numbers are being collected"

# Only visible from here. A scrape endpoint that answers in a test proves nothing about
# whether anything is collecting it, and monitoring that has quietly stopped covering a
# service looks exactly like a service with nothing wrong.
if command -v docker > /dev/null 2>&1 && docker compose ps prometheus > /dev/null 2>&1; then
    # The gateway is the edge, so its own numbers must not be readable by whoever can reach
    # the API. Health still is, because a probe holds no credentials.
    call 401 GET "$GATEWAY/actuator/prometheus" > /dev/null
    call 200 GET "$GATEWAY/actuator/health" > /dev/null
    pass "the gateway answers what is up, and says nothing about what it is doing"

    # The first scrape has to have happened, and the stack may have only just come up.
    for attempt in $(seq 1 20); do
        up=$(call 200 GET "$PROMETHEUS/api/v1/query?query=up%7Bjob%3D%22mizan%22%7D"             | "$PYTHON" -c '
import json, sys
answer = json.load(sys.stdin)["data"]["result"]
print(sum(1 for series in answer if series["value"][1] == "1"))')
        [ "$up" -ge 8 ] && break
        sleep 3
    done
    [ "$up" -ge 8 ] || fail "only $up of the platform's services are being scraped"
    pass "$up services are being scraped, every one of them answering"

    # A metric that does not say which service it came from is a metric that loses its
    # meaning the moment anything adds two of them together.
    named=$(call 200 GET "$PROMETHEUS/api/v1/query?query=count%20by%20(service)%20(jvm_info)"         | "$PYTHON" -c '
import json, sys
print(len([s for s in json.load(sys.stdin)["data"]["result"]
           if s["metric"].get("service")]))')
    [ "${named:-0}" -ge 8 ] || fail "only $named service(s) tag their metrics with their name"
    pass "and every one of them says which service its numbers are about"
else
    note "no Prometheus in this stack, so nothing was checked about collection"
fi

# ---------------------------------------------------------------------------------------
step "19. The numbers being collected are about payments, not about the JVM"

# Only provable from here. Every one of these was driven by this run: the approval in step 7,
# the decline in step 8, the queues the earlier steps filled. A unit test can prove a counter
# increments when something calls it; only a real run proves the thing that should call it
# does, in the assembled platform, and that a scrape carried the number out of the process.
if command -v docker > /dev/null 2>&1 && docker compose ps prometheus > /dev/null 2>&1; then
    promql() {
        call 200 GET "$PROMETHEUS/api/v1/query?query=$1" | "$PYTHON" -c '
import json, sys
answer = json.load(sys.stdin)["data"]["result"]
print(answer[0]["value"][1] if answer else "")'
    }

    # The scrape interval has to have passed at least once since the payments above.
    for attempt in $(seq 1 20); do
        approved=$(promql 'mizan_payments_authorizations_total%7Boutcome%3D%22approved%22%7D')
        [ -n "$approved" ] && [ "${approved%%.*}" -ge 1 ] && break
        sleep 3
    done
    [ -n "$approved" ] && [ "${approved%%.*}" -ge 1 ] \
        || fail "nothing counted an approved authorization, though this run made several"
    pass "the approvals this run made are counted, and collected"

    # The reason, not a total. "Declines are up" is a fact nobody can act on; "declines for
    # insufficient funds are up" is a conversation with a merchant, and "declines for do not
    # honour are up" is a conversation with an acquirer.
    refused=$(promql \
        'mizan_payments_authorizations_total%7Boutcome%3D%22declined%22%2Creason%3D%22insufficient_funds%22%7D')
    [ -n "$refused" ] && [ "${refused%%.*}" -ge 1 ] \
        || fail "the decline in step 8 was not counted under the reason the bank gave"
    pass "and the decline is counted under insufficient_funds, not under a total"

    # Every queue a person empties, as a number. A queue nobody can see the length of is a
    # queue that is discovered when a merchant asks why they have not been paid.
    for queue in mizan_payments_needing_a_person \
                 mizan_reviews_waiting \
                 mizan_deadletters_outstanding \
                 mizan_webhooks_waiting \
                 mizan_webhooks_lag_seconds \
                 mizan_reconciliation_differences_waiting \
                 mizan_settlement_captures_waiting; do
        [ -n "$(promql "$queue")" ] \
            || fail "$queue is not being collected, so its backlog is invisible"
    done
    pass "every queue that waits for a person reports its length, and zero when it is empty"

    # The books, on a timer, in the running platform. books-balance.sh below asks the service
    # directly; this asks whether anything would have noticed without being asked.
    sound=$(promql mizan_ledger_sound)
    [ "$sound" = "1" ] \
        || fail "the ledger drift gauge says $sound, so either the books moved or nothing checked"
    pass "the ledger checks itself on a timer, and the answer leaves the process"

    # Latency as a distribution, which is the only form of it worth having. A count and a sum
    # give a mean, and a mean hides a tail by construction: one request in twenty taking two
    # seconds moves it by a tenth of a second. This platform has a wide tail under load, and
    # until these buckets existed it was visible only to k6, from outside, during a load run.
    slowEnd=$(promql 'histogram_quantile%280.95%2C%20sum%20by%20%28le%29%20%28rate%28http_server_requests_seconds_bucket%5B5m%5D%29%29%29')
    [ -n "$slowEnd" ] && [ "$slowEnd" != "NaN" ]         || fail "no latency buckets are collected, so the slow end of this platform is invisible"
    pass "latency is published as buckets, so a p95 can be asked for at any time and not only under load"

    # The one that is not about any single metric. Cardinality is how a monitoring system
    # falls over, and it falls over at the moment somebody needs it.
    offending=$(call 200 GET \
        "$PROMETHEUS/api/v1/labels?match%5B%5D=%7B__name__%3D~%22mizan_.%2B%22%7D" \
        | "$PYTHON" -c '
import json, sys
never = {"merchant", "merchantid", "merchant_id", "payment", "paymentid", "payment_id",
         "amount", "money", "card", "reference", "user", "userid", "user_id", "email"}
found = [label for label in json.load(sys.stdin)["data"] if label.lower() in never]
print(",".join(found))')
    [ -z "$offending" ] \
        || fail "these labels have no upper bound and are somebody's business: $offending"
    pass "and no number is labelled with a merchant, a payment, a card or an amount"
else
    note "no Prometheus in this stack, so nothing was checked about what is measured"
fi

# ---------------------------------------------------------------------------------------
step "20. The dashboards came up already knowing what to show"

# A dashboard somebody built once in a running Grafana is a dashboard nobody can review and
# nobody can bring back. These come from files in this repository, and what cannot be proved
# from the files is exactly what is checked here: that Grafana read them, that the datasource
# it was handed actually reaches Prometheus, and that every metric every panel asks for is a
# metric that exists. A panel naming a metric that does not renders as an empty chart, and an
# empty chart looks exactly like a quiet platform.
if command -v docker > /dev/null 2>&1 && docker compose ps grafana > /dev/null 2>&1; then
    call 200 GET "$GRAFANA/api/health" > /dev/null
    pass "grafana is up"

    # Provisioned, not clicked. If this is missing, every panel on both dashboards is blank.
    source=$(call 200 GET "$GRAFANA/api/datasources/uid/mizan-prometheus")
    [ "$(printf '%s' "$source" | field type)" = "prometheus" ] \
        || fail "the provisioned datasource is not a prometheus one: $source"
    pass "its datasource was provisioned from the repository, not set up by hand"

    # And it works. A datasource with the wrong URL provisions perfectly and fails only when
    # somebody opens a panel, which is the one moment nobody wants to debug it.
    through=$(call 200 GET \
        "$GRAFANA/api/datasources/proxy/uid/mizan-prometheus/api/v1/query?query=up" \
        | field status)
    [ "$through" = "success" ] || fail "grafana cannot query prometheus through it: $through"
    pass "and grafana can actually read the numbers through it"

    # Both of them, by uid, because two dashboards sharing one would silently replace it.
    for dashboard in mizan-platform-up mizan-is-it-everybody; do
        call 200 GET "$GRAFANA/api/dashboards/uid/$dashboard" > /dev/null
    done
    pass "both dashboards are loaded, and each is where its link says it is"

    # The acceptance criterion, asked of the running platform rather than of the files. The
    # static test covers the metrics this repository owns; this covers every other name a
    # panel uses — the JVM's, Spring's, the scrape's — which no file here can vouch for.
    missing=$(call 200 GET "$PROMETHEUS/api/v1/label/__name__/values" | "$PYTHON" -c '
import json, os, re, sys

published = set(json.load(sys.stdin)["data"])
promql = {
    "sum", "min", "max", "avg", "count", "count_values", "stddev", "stdvar", "topk",
    "bottomk", "quantile", "group", "by", "without", "on", "ignoring", "group_left",
    "group_right", "rate", "irate", "increase", "delta", "idelta", "deriv",
    "predict_linear", "resets", "changes", "abs", "ceil", "floor", "round", "clamp",
    "clamp_max", "clamp_min", "exp", "ln", "log2", "log10", "sqrt", "sgn", "absent",
    "absent_over_time", "histogram_quantile", "label_replace", "label_join", "time",
    "timestamp", "vector", "scalar", "sort", "sort_desc", "sum_over_time", "avg_over_time",
    "min_over_time", "max_over_time", "count_over_time", "last_over_time", "offset",
    "and", "or", "unless", "bool",
}

wanted = {}
folder = sys.argv[1]
for name in sorted(os.listdir(folder)):
    if not name.endswith(".json"):
        continue
    with open(os.path.join(folder, name), encoding="utf-8") as handle:
        for panel in json.load(handle)["panels"]:
            for target in panel.get("targets", []):
                expression = target.get("expr", "")
                # Label selectors, windows, and the labels a by() groups on. All three look
                # exactly like a metric name to a regular expression and none of them is one.
                expression = re.sub(r"\{[^}]*}", " ", expression)
                expression = re.sub(r"\[[^]]*]", " ", expression)
                expression = re.sub(
                    r"\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)",
                    " ", expression)
                for token in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", expression):
                    if token not in promql:
                        wanted.setdefault(token, []).append("%s/%s" % (name, panel["title"]))

for metric in sorted(set(wanted) - published):
    print("  %s, asked for by %s" % (metric, wanted[metric][0]), file=sys.stderr)
print(len(set(wanted) - published))' "$(dirname "$0")/../deploy/local/grafana/dashboards")
    [ "$missing" = "0" ] || fail "$missing metric(s) on the dashboards do not exist"
    pass "and every metric every panel asks for is one the platform really publishes"
else
    note "no Grafana in this stack, so nothing was checked about the dashboards"
fi

# ---------------------------------------------------------------------------------------
step "21. One payment, one trace, across every hop it took"

# The part no suite can reach. A test with one process in it can prove a header is written; it
# cannot prove that six services, two of them reached only across a Kafka topic, ended up in
# one trace that a person can open. The hop that matters is the outbox one: by the time the
# relay publishes, the request that caused the event has been finished for seconds or minutes,
# on a thread with no relation to it, and nothing but the stored row was present for both ends.
if command -v docker > /dev/null 2>&1 && docker compose ps tempo > /dev/null 2>&1; then
    traced=$(authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments" \
        "{\"amount\":$AMOUNT,\"currency\":\"TRY\",\"reference\":\"order-$RUN-traced\"}")
    TRACED=$(printf '%s' "$traced" | field id)

    # Raw curl rather than the helper, because what is wanted here is a response header.
    headers=$(curl -sS -D - -o /dev/null \
        -X POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$TRACED/authorize" \
        -H "Authorization: Bearer $AUTH" -H "Idempotency-Key: $(key)" \
        -H 'Content-Type: application/json' -d '{"card":"4000000000000000"}')

    TRACE=$(printf '%s' "$headers" | "$PYTHON" -c '
import re, sys
found = re.search(r"(?im)^x-trace-id:\s*([0-9a-f]{32})\s*$", sys.stdin.read())
print(found.group(1) if found else "")')
    [ -n "$TRACE" ] || fail "the edge did not hand back a trace id: $headers"
    pass "the edge answered with the trace of the request: $TRACE"

    # The collector waits for the slow half of a trace before deciding about it, and Tempo has
    # to have written it. Both are seconds, and both are the reason this polls.
    for attempt in $(seq 1 25); do
        found=$(curl -sS -o /dev/null -w '%{http_code}' "$TEMPO/api/traces/$TRACE")
        [ "$found" = "200" ] && break
        sleep 2
    done
    [ "$found" = "200" ] || fail "the trace never reached the store"

    spans=$(call 200 GET "$TEMPO/api/traces/$TRACE")

    services=$(printf '%s' "$spans" | "$PYTHON" -c '
import json, sys
document = json.load(sys.stdin)
names = set()
for batch in document.get("batches", []):
    for attribute in batch["resource"]["attributes"]:
        if attribute["key"] == "service.name":
            names.add(attribute["value"]["stringValue"])
print(" ".join(sorted(names)))')
    note "in this trace: $services"

    for hop in gateway payment-service bank-simulator; do
        case " $services " in
            *" $hop "*) ;;
            *) fail "$hop is not in the trace, so the trace stops before it" ;;
        esac
    done
    pass "the edge, the platform and the acquirer are one trace, not three"

    # The one that cannot be reconstructed by hand.
    across=$(printf '%s' "$spans" | "$PYTHON" -c '
import json, sys
document = json.load(sys.stdin)
crossed = []
for batch in document.get("batches", []):
    service = [a["value"]["stringValue"] for a in batch["resource"]["attributes"]
               if a["key"] == "service.name"][0]
    for scope in batch.get("scopeSpans", []):
        for span in scope.get("spans", []):
            if "mizan." in span.get("name", "") and "process" in span.get("name", ""):
                crossed.append(service)
print(" ".join(sorted(set(crossed))))')
    [ -n "$across" ] || fail "no consumer continued the trace across the topic"
    pass "and it continues across Kafka into $across, minutes after the request ended"

    # The two ids, connected. A problem report names one of them and whoever picks it up needs
    # the other; neither replaces the other, which is why both are here.
    correlation=$(printf '%s' "$headers" | "$PYTHON" -c '
import re, sys
found = re.search(r"(?im)^x-correlation-id:\s*(\S+)\s*$", sys.stdin.read())
print(found.group(1) if found else "")')
    noted=$(printf '%s' "$spans" | "$PYTHON" -c '
import json, sys
wanted = sys.argv[1]
document = json.load(sys.stdin)
for batch in document.get("batches", []):
    for scope in batch.get("scopeSpans", []):
        for span in scope.get("spans", []):
            for attribute in span.get("attributes", []):
                if attribute["key"] == "mizan.correlation_id":
                    if attribute["value"].get("stringValue") == wanted:
                        print("yes")
                        sys.exit()
print("no")' "$correlation")
    [ "$noted" = "yes" ] || fail "the correlation id $correlation is on no span in the trace"
    pass "and the id a person reads out is on the trace, so either one finds the other"

    # Nothing in a span that would not be allowed in a log. A trace is stored for days and
    # read by whoever is debugging, which makes it exactly the wrong place for a card number.
    leaked=$(printf '%s' "$spans" | "$PYTHON" -c '
import json, re, sys

never = {"card", "pan", "card_number", "cardnumber", "password", "secret", "token",
         "authorization", "api_key", "apikey", "cvv", "cvc"}
looks_like_a_card = re.compile(r"(?<!\d)(?:\d[ -]?){13,19}(?!\d)")

document = json.load(sys.stdin)
found = []
for batch in document.get("batches", []):
    for scope in batch.get("scopeSpans", []):
        for span in scope.get("spans", []):
            for attribute in span.get("attributes", []):
                key = attribute["key"].lower().rsplit(".", 1)[-1]
                value = str(attribute["value"].get("stringValue", ""))
                if key in never:
                    found.append("%s carries %s" % (span["name"], attribute["key"]))
                elif looks_like_a_card.search(value):
                    found.append("%s has something card shaped in %s"
                                 % (span["name"], attribute["key"]))
for line in found:
    print("  " + line, file=sys.stderr)
print(len(found))')
    [ "$leaked" = "0" ] || fail "$leaked span attribute(s) hold something they should not"
    pass "and no span carries a card, a token or a secret"
else
    note "no Tempo in this stack, so nothing was checked about tracing"
fi

# ---------------------------------------------------------------------------------------
step "22. Nothing this run wrote to a log should have been written down"

# The one check that could not be a code review. A card reaches a log through a line somebody
# added during an incident, a toString on a request object, an exception that quotes what it
# was given, or a framework logging a body nobody asked it to. None of those looks wrong where
# it is written, and a log store keeps what it is given for months.
#
# So the real thing is driven — this whole run has been driving it — and then everything eight
# services wrote is read back and searched. Not for one card: for anything shaped like one.
if command -v docker > /dev/null 2>&1 && docker compose ps payment-service > /dev/null 2>&1; then
    # The platform's own services, not the infrastructure beside them. Tempo's block ids and
    # Kafka's offsets are not something this platform wrote, and scanning them only finds
    # hexadecimal that happens to look like digits.
    written=$(docker compose logs --no-color --since 30m         gateway identity-service ledger-service payment-service risk-service         notification-service settlement-service bank-simulator 2>/dev/null)
    lines=$(printf '%s' "$written" | wc -l)
    [ "${lines:-0}" -gt 50 ] || fail "only $lines log lines to check, which proves nothing"
    note "$lines lines from every service in the stack"

    leaked=$(printf '%s' "$written" | "$PYTHON" -c '
import re, sys

# What a card looks like, rather than what a long number looks like: an issuer digit, then
# twelve to eighteen more however they were spaced, and passing the Luhn check. Without all
# three this flags every epoch timestamp and every Kafka offset, and a check that cries wolf
# is a check somebody switches off.
# Not glued to letters either side, so the digits inside a UUID or a hex id are not a card.
card_shaped = re.compile(r"(?<![0-9A-Za-z-])([3-6](?:[ -]?[0-9]){12,18})(?![0-9A-Za-z])")

def luhn(digits):
    total, doubling = 0, False
    for digit in reversed([int(d) for d in digits]):
        if doubling:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit
        doubling = not doubling
    return total % 10 == 0

never = ("a-shared-secret-for-local-runs", "bWl6YW4t", "\"card\":\"", "cardNumber", "pan=",
         "BEGIN PRIVATE KEY", "password=")

found = []
for line in sys.stdin.read().splitlines():
    for secret in never:
        if secret in line:
            found.append("names %s: %s" % (secret, line[:160]))
    for match in card_shaped.finditer(line):
        digits = re.sub(r"[ -]", "", match.group())
        if luhn(digits):
            found.append("card shaped %s: %s" % (digits, line[:160]))

for line in found[:10]:
    print("  " + line, file=sys.stderr)
print(len(found))')
    [ "$leaked" = "0" ] || fail "$leaked log line(s) hold something that should never be written"
    pass "no card, no key and no secret in anything the platform wrote"

    # Both ids on the lines that have a request behind them. A line nobody can group with the
    # request that caused it is a line nobody can use.
    attributed=$(printf '%s' "$written" | "$PYTHON" -c '
import re, sys
# The readable pattern is "[<correlation> <trace>] "; the JSON one carries them as fields.
readable = re.compile(r"\[[0-9a-f-]{8,} [0-9a-f]{32}\]")
structured = re.compile(r"\"(?:correlationId|trace_id|traceId)\"")
text = sys.stdin.read()
print(len(readable.findall(text)) + len(structured.findall(text)))')
    [ "${attributed:-0}" -ge 1 ] \
        || fail "no log line carries both a correlation id and a trace id"
    pass "and $attributed line(s) carry both the id a person reads out and the id that opens a trace"
else
    note "no Compose stack, so nothing was read back out of the logs"
fi

# ---------------------------------------------------------------------------------------
step "23. What wakes somebody up is loaded, and each rule fires when it should"

# A rule that parses is not a rule that works, and the one that never fires looks exactly like
# a platform that is fine. So the rules are tested by Prometheus's own evaluator, inside the
# Prometheus this stack runs, and then the running instance is asked whether it loaded them.
if command -v docker > /dev/null 2>&1 && docker compose ps prometheus > /dev/null 2>&1; then
    MSYS_NO_PATHCONV=1 docker compose exec -T prometheus \
        promtool test rules /etc/prometheus/alerts-test.yml > /dev/null 2>&1 \
        || {
            MSYS_NO_PATHCONV=1 docker compose exec -T prometheus \
                promtool test rules /etc/prometheus/alerts-test.yml >&2
            fail "an alert rule does not fire, or fires, when its test says otherwise"
        }
    pass "every rule fires on the condition it exists for, and stays quiet on the harmless one"

    loaded=$(call 200 GET "$PROMETHEUS/api/v1/rules?type=alert" | "$PYTHON" -c '
import json, sys
groups = json.load(sys.stdin)["data"]["groups"]
names = sorted(rule["name"] for group in groups for rule in group["rules"])
print(" ".join(names))')
    for rule in LedgerDoesNotBalance AnEventWasSetAside TheBankDisagreesAndNobodyHasRuled \
                AuthorizationsCollapsed AServiceIsNotAnswering; do
        case " $loaded " in
            *" $rule "*) ;;
            *) fail "$rule is not loaded by the running Prometheus, so it cannot fire" ;;
        esac
    done
    pass "and the running Prometheus has loaded all of them: $loaded"

    # The one that must never be firing on a healthy run. If it is, either the books moved or
    # the rule is wrong, and both are worth failing on.
    firing=$(call 200 GET "$PROMETHEUS/api/v1/alerts" | "$PYTHON" -c '
import json, sys
alerts = json.load(sys.stdin)["data"]["alerts"]
print(" ".join(sorted({a["labels"]["alertname"] for a in alerts if a["state"] == "firing"})))')
    case " $firing " in
        *" LedgerDoesNotBalance "*) fail "the ledger alert is firing on a run that balanced" ;;
    esac
    pass "and the ledger alert is quiet, as it should be when the books balance"
else
    note "no Prometheus in this stack, so nothing was checked about alerting"
fi

# ---------------------------------------------------------------------------------------
step "24. Each image holds the service and nothing an attacker could use"

# Asked of the images that are actually running, not of the Dockerfile. A Dockerfile that says
# distroless and a base image swapped back in a hurry look identical in a code review, and
# only the built image says which one is true.
if command -v docker > /dev/null 2>&1 && docker compose ps payment-service > /dev/null 2>&1; then
    for service in gateway identity-service ledger-service payment-service risk-service \
                   notification-service settlement-service bank-simulator; do
        image=$(docker compose images -q "$service" 2>/dev/null | head -1)
        [ -n "$image" ] || fail "$service has no image to inspect"

        user=$(docker image inspect "$image" --format '{{.Config.User}}')
        case "$user" in
            nonroot|65532|65532:65532) ;;
            *) fail "$service runs as '${user:-root}', and a service has no business being root" ;;
        esac

        # No shell means a compromised process has nothing to run commands with. An exit of
        # 0 here means one was found, which is the failure.
        if MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh "$image" -c true \
                > /dev/null 2>&1; then
            fail "$service's image has a shell in it"
        fi
    done
    pass "all eight run as a non root user, in an image with no shell to use"
else
    note "no Compose stack, so no image was inspected"
fi

# ---------------------------------------------------------------------------------------
# 25. The books balance, asked of everything that has ever been written. Its own script,
# because CI runs it again after the browser journey and the demo seed, over data that three
# different things produced and none of them wrote in order to make it pass.
"$(dirname "$0")/books-balance.sh"

printf '\n%s%s  The platform works end to end.%s\n\n' "$BOLD" "$GREEN" "$OFF"
