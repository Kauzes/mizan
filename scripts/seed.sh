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

step "Karaköy Kahve"
merchant "Karakoy Kahve" "Ada Lovelace" "karakoy"
note "sign in as $EMAIL / $PASSWORD"
note "merchant $MERCHANT"
payment 125000 "order-$RUN-1" "$GOOD_CARD" capture
payment 89500  "order-$RUN-2" "$GOOD_CARD" capture
payment 45000  "order-$RUN-3" "$GOOD_CARD" void
payment 210000 "order-$RUN-4" "$GOOD_CARD"
payment 67500  "order-$RUN-5" "$NO_FUNDS"
payment 30000  "order-$RUN-6"
payment 155000 "order-$RUN-7" "$SLOW_APPROVE"

step "Moda Kitapçısı"
merchant "Moda Kitapcisi" "Grace Hopper" "moda"
note "sign in as $EMAIL / $PASSWORD"
note "merchant $MERCHANT"
payment 74000  "order-$RUN-8"  "$GOOD_CARD" capture
payment 19900  "order-$RUN-9"  "$GOOD_CARD"
payment 250000 "order-$RUN-10" "$NO_FUNDS"

step "The books"
note "$(call 200 GET "$LEDGER/actuator/ledgerintegrity" | field summary)"

printf '\n%sSeeded.%s Browse the APIs at %s/swagger-ui.html\n\n' "$BOLD" "$OFF" "$GATEWAY"
