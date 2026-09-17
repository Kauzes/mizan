#!/usr/bin/env bash
#
# Loads demo data into a running stack: two merchants with real books and payments in every
# state a payment can be in.
#
# For looking at, not for asserting on. The smoke check is what proves the platform works;
# this is what gives somebody opening Swagger UI, or a console later, something to open onto.
# It prints the credentials it created, because data nobody can sign in as is not a demo.
#
# Usage:  docker compose up -d --wait  &&  ./scripts/seed.sh

DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/mizan.sh
. "$DIRECTORY/mizan.sh"

RUN="$(date +%s)"
PASSWORD="correct-horse-battery-staple"

GOOD_CARD="4000000000000000"
# A different card per customer. Ten payments from one card in as many seconds is a signal the scorer
# exists to catch (RAPID_ATTEMPTS), and a demo where every ordinary payment is held for review is a demo
# of the scorer rather than of the platform. Anything not ending in a behaviour code approves.
CARDS_ISSUED=0
another_card() {
    CARDS_ISSUED=$((CARDS_ISSUED + 1))
    printf '40000000000011%02d' "$CARDS_ISSUED"
}
NO_FUNDS="4000000000000002"
SLOW_APPROVE="4000000000000069"

printf '%sSeeding demo data%s  %s%s%s\n' "$BOLD" "$OFF" "$DIM" "$GATEWAY" "$OFF"

# Signs a merchant up and leaves EMAIL, MERCHANT and AUTH pointing at them.
merchant() {
    local name="$1" person="$2" slug="$3"
    EMAIL="$slug-$RUN@mizan.local"

    local registered
    registered=$(call 201 POST "$GATEWAY/api/v1/merchants" \
        "{\"merchantName\":\"$name\",\"fullName\":\"$person\",
          \"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
        -H "Idempotency-Key: $(key)")
    MERCHANT=$(printf '%s' "$registered" | field merchant.id)

    AUTH=$(call 200 POST "$GATEWAY/api/v1/tokens" \
        "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | field accessToken)

    authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/accounts" \
        '{"code":"settlement.try","name":"Owed to the merchant, TRY",
          "type":"LIABILITY","currency":"TRY"}' > /dev/null
}

# Creates a payment and leaves it wherever the card and the verb take it.
payment() {
    local amount="$1" reference="$2" card="${3:-}" verb="${4:-}"

    local created id
    created=$(authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments" \
        "{\"amount\":$amount,\"currency\":\"TRY\",\"reference\":\"$reference\"}")
    id=$(printf '%s' "$created" | field id)
    PAYMENT="$id"

    if [ -z "$card" ]; then
        note "$reference — left as an intent, nobody contacted"
        return
    fi

    # Not `authed`, because two of these outcomes are not the 200 it insists on. The slow
    # card times out on purpose and answers 504, leaving the payment in
    # AUTHORIZATION_UNKNOWN until the sweep asks the acquirer what it did — a state worth
    # having on screen, since it is the one people assume cannot happen.
    local newline response http body
    newline=$'\n'
    response=$(curl -sS -w "${newline}%{http_code}" -X POST \
        "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$id/authorize" \
        -H "Authorization: Bearer $AUTH" \
        -H "Idempotency-Key: $(key)" \
        -H 'Content-Type: application/json' \
        -d "{\"card\":\"$card\"}")
    http="${response##*"$newline"}"
    body="${response%"$newline"*}"

    STATUS=$(printf '%s' "$body" | field status 2> /dev/null || true)

    if [ "$http" = "504" ]; then
        note "$reference — the acquirer did not answer; nobody knows yet, and the sweep will ask"
        return
    fi
    [ "$http" = "200" ] || { printf '  authorize answered %s\n' "$http" >&2; exit 1; }

    # A decline is a 200 as well: the request succeeded, and the acquirer said no. Reading
    # the status code alone is how a refused payment gets labelled as an approved one.
    if [ "$(printf '%s' "$body" | field status)" = "DECLINED" ]; then
        note "$reference — declined: $(printf '%s' "$body" | field declineReason)"
        return
    fi

    # Risk held it, so there is nothing to capture or void until somebody rules on it.
    if [ "$STATUS" = "HELD_FOR_REVIEW" ]; then
        note "$reference — held for review; nobody has been charged"
        return
    fi

    case "$verb" in
        capture)
            authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$id/capture" > /dev/null
            note "$reference — captured, and in the books"
            ;;
        void)
            authed 200 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$id/void" \
                '{"reason":"the customer changed their mind"}' > /dev/null
            note "$reference — voided, and the books are untouched"
            ;;
        *)
            note "$reference — authorized, waiting to be captured or voided"
            ;;
    esac
}

takings() {
    local from="$1" i
    for i in $(seq 1 10); do
        payment $((from + i * 15000)) "order-$RUN-$2-$i" "$(another_card)" capture > /dev/null
    done
    note "ten ordinary takings, so this merchant has a usual"
}

step "Karaköy Kahve"
merchant "Karakoy Kahve" "Ada Lovelace" "karakoy"
note "sign in as $EMAIL / $PASSWORD"
note "merchant $MERCHANT"
takings 50000 karakoy
# Kept, so the steps below can come back to this merchant: the refund and the held payment belong on the
# one somebody signs in as first, not on whichever was created last.
KARAKOY_MERCHANT="$MERCHANT"
KARAKOY_AUTH="$AUTH"
KARAKOY_EMAIL="$EMAIL"
payment 125000 "order-$RUN-1" "$(another_card)" capture
payment 89500  "order-$RUN-2" "$(another_card)" capture
payment 45000  "order-$RUN-3" "$(another_card)" void
payment 210000 "order-$RUN-4" "$(another_card)"
payment 67500  "order-$RUN-5" "$NO_FUNDS"
payment 30000  "order-$RUN-6"
payment 155000 "order-$RUN-7" "$SLOW_APPROVE"

step "Moda Kitapçısı"
merchant "Moda Kitapcisi" "Grace Hopper" "moda"
note "sign in as $EMAIL / $PASSWORD"
note "merchant $MERCHANT"
takings 40000 moda
payment 74000  "order-$RUN-8"  "$(another_card)" capture
payment 19900  "order-$RUN-9"  "$(another_card)"
payment 250000 "order-$RUN-10" "$NO_FUNDS"

step "Money going back"
MERCHANT="$KARAKOY_MERCHANT"
AUTH="$KARAKOY_AUTH"
EMAIL="$KARAKOY_EMAIL"
# A refund is a new movement that names the capture it reverses, so a demo without one is a demo of
# half the ledger. Partial, because that is the case people expect to be missing.
payment 96000 "order-$RUN-11" "$(another_card)" capture
authed 201 POST "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT/refunds"     "{\"amount\":36000,\"currency\":\"TRY\",\"reference\":\"refund-$RUN-1\",
      \"reason\":\"one of the three books came back\"}" > /dev/null
note "order-$RUN-11 — 360.00 of 960.00 refunded, as a second entry rather than an edit"

step "A payment waiting for a person"
# Held by the scorer rather than by a switch: this card was declined here a moment ago, and the amount
# is far larger than this merchant's usual and exactly round. Three signals that add up past the review
# threshold — which is how a real one arrives, and why the reasons are on the screen beside it.
payment 12500 "order-$RUN-12a" "$NO_FUNDS"
# Risk learns what happened to a payment from the events it consumes, so the takings and the decline
# above have to have been observed before the next one is scored. A pause rather than a retry loop,
# because this is demo data: if it is missed, the note below says so instead of pretending.
sleep 10
payment 1000000 "order-$RUN-12" "$NO_FUNDS"
if [ "$STATUS" = "HELD_FOR_REVIEW" ]; then
    note "order-$RUN-12 — held for review: $(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/payments/$PAYMENT" | field riskReasons)"
else
    note "order-$RUN-12 — $STATUS (the scorer did not hold it this time; the signals are timing based)"
fi

step "Today closed, and the bank's statement compared with it"
DAY=$("$PYTHON" -c '
import datetime, zoneinfo
print(datetime.datetime.now(zoneinfo.ZoneInfo("Europe/Istanbul")).date())')
note "$(authed 200 GET "$GATEWAY/api/v1/merchants/$MERCHANT/settlements" | "$PYTHON" -c '
import json, sys
batches = json.load(sys.stdin)
print(len(batches), "settlement batch(es) for this merchant" if len(batches) != 1 else "settlement batch for this merchant")')"
found=$(call 200 POST "$SETTLEMENT/actuator/reconciliation/$DAY" '{}')
note "the statement: $(printf '%s' "$found" | field matched) matched, $(printf '%s' "$found" | field missingFromStatement) missing, $(printf '%s' "$found" | field extraOnStatement) extra, $(printf '%s' "$found" | field amountsDiffer) differing"
note "the differences are in the console's reconciliation queue, waiting for somebody to rule"

step "More than one day"
# The API cannot create a payment in the past, and the console's overview groups by the day a payment
# was created, so everything seeded lands in one tall column. This is the only place in this script that
# writes to a database directly. It moves only these merchants' payments, and it moves nothing in the
# ledger: the books say when the money actually moved, and a ledger rewritten to make a screenshot look
# better is not a ledger.
if command -v docker > /dev/null 2>&1; then
    aged=$(docker exec mizan-postgres-1 psql -U mizan -d payment -Atc "
        update payment
           set created_at = created_at - make_interval(days => (abs(hashtext(reference)) % 6) + 1)
         where reference like 'order-$RUN-%'
           and reference not in ('order-$RUN-11', 'order-$RUN-12')
        returning 1" | grep -c 1 || true)
    note "$aged payment(s) moved back between one and six days, so the overview is a week and not a column"
    note "the refund and the held payment stay on today, where somebody looking at the demo will find them"
fi

step "The books"
note "$(call 200 GET "$LEDGER/actuator/ledgerintegrity" | field summary)"

printf '\n%sSeeded.%s Browse the APIs at %s/swagger-ui.html\n\n' "$BOLD" "$OFF" "$GATEWAY"
