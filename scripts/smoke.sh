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
step "11. The books balance"

integrity=$(call 200 GET "$LEDGER/actuator/ledgerintegrity")
sound=$(printf '%s' "$integrity" | field sound)
[ "$sound" = "True" ] || [ "$sound" = "true" ] || {
    printf '%s\n' "$integrity" >&2
    fail "the ledger does not balance"
}
note "$(printf '%s' "$integrity" | field summary)"
pass "every currency sums to zero and every balance agrees with its postings"

printf '\n%s%s  The platform works end to end.%s\n\n' "$BOLD" "$GREEN" "$OFF"
