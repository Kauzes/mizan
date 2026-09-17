#!/usr/bin/env bash
#
# Takes a payment and gives part of it back, through the gateway, and writes down exactly what went over
# the wire as docs/api/worked-example.md.
#
# Every request and response in that document was made by this script against a running platform. Nothing
# in it is typed by hand, which is the point: a worked example that is written by a person is a worked
# example that stops being true the first time a field is renamed, and the reader who finds that out is a
# merchant trying to take their first payment.
#
# CI runs it with --check against the stack it started. That regenerates the document, puts the volatile
# parts (ids, timestamps, tokens, dates) back to fixed placeholders, and fails if anything else differs.
#
# Usage:  ./scripts/worked-example.sh          writes docs/api/worked-example.md
#         ./scripts/worked-example.sh --check  fails if the committed document is not what this produces

DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/mizan.sh
. "$DIRECTORY/mizan.sh"

ROOT="$(cd "$DIRECTORY/.." && pwd)"
DOCUMENT="$ROOT/docs/api/worked-example.md"
MODE="${1:-write}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
OUT="$WORK/worked-example.md"

RUN="$(date +%s)-$RANDOM"
EMAIL="worked-example-$RUN@mizan.local"
PASSWORD="correct-horse-battery-staple"
MERCHANT_NAME="Worked Example Co"

say() { printf '%s\n' "$*" >> "$OUT"; }

pretty() {
    "$PYTHON" -c '
import json, sys
body = sys.stdin.read().strip()
if not body:
    print("(no body)")
else:
    try:
        print(json.dumps(json.loads(body), indent=2))
    except ValueError:
        print(body)
'
}

# One request, written down as it happened: what was sent, and what came back.
#
#   call <method> <path> <body|-> [header...]
call() {
    local method="$1" path="$2" body="$3"
    shift 3

    local headers=("$@")
    local response status
    local headerFile="$WORK/headers"

    if [ "$body" = "-" ]; then
        response=$(curl -s -D "$headerFile" -o "$WORK/body" -w '%{http_code}' -X "$method" "$GATEWAY$path" \
            "${headers[@]/#/-H}")
    else
        response=$(curl -s -D "$headerFile" -o "$WORK/body" -w '%{http_code}' -X "$method" "$GATEWAY$path" \
            -H 'Content-Type: application/json' "${headers[@]/#/-H}" -d "$body")
    fi
    status="$response"

    say '```http'
    say "$method $path HTTP/1.1"
    [ "$body" = "-" ] || say "Content-Type: application/json"
    local header
    for header in "${headers[@]}"; do
        say "$header"
    done
    if [ "$body" != "-" ]; then
        say ""
        printf '%s' "$body" | pretty >> "$OUT"
    fi
    say '```'
    say ""
    say '```http'
    say "HTTP/1.1 $status"
    # Only the headers a reader acts on. Everything else is noise that changes between runs.
    grep -iE '^(location|retry-after|x-correlation-id|content-type):' "$WORK/headers" |
        tr -d '\r' >> "$OUT" || true
    say ""
    pretty < "$WORK/body" >> "$OUT"
    say '```'
    say ""

    LAST_STATUS="$status"
    LAST_BODY="$(cat "$WORK/body")"
}

field() {
    printf '%s' "$1" | "$PYTHON" -c '
import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    value = value[key]
print(value)
' "$2"
}

# ---------------------------------------------------------------------------------------------------
cat > "$OUT" <<'PREAMBLE'
# Taking a payment, and giving part of it back

Every request and response below was made against a running platform by
[`scripts/worked-example.sh`](../../scripts/worked-example.sh), which writes this file. CI runs the same
script with `--check` against the stack it starts, so this document cannot drift from the API: if a field
is renamed and this is not regenerated, the build fails.

Ids, timestamps and tokens are replaced with fixed placeholders so that two runs produce the same
document. Everything else is exactly what went over the wire.

Everything goes through the gateway on `http://localhost:8080`. Nothing here touches a service directly,
because nothing outside the platform can.

PREAMBLE

say "## 1. A merchant registers"
say ""
say "Registering is the one write that needs no token. It creates the merchant, the owner's user and the"
say "owner's role in one request."
say ""
say "**Every write takes an \`Idempotency-Key\`.** It is a key you choose, and it is how you ask again"
say "safely: the same key with the same body is answered with what was already done rather than doing it"
say "twice. Use a fresh one per operation, keep it as long as you might retry, and never reuse one for a"
say "different request — that is refused, and section 6 shows what that looks like."
say ""
call POST /api/v1/merchants \
    "{\"merchantName\":\"$MERCHANT_NAME\",\"fullName\":\"Ada Lovelace\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
    "Idempotency-Key: register-a-merchant-$RUN"
[ "$LAST_STATUS" = "201" ] || { echo "registering answered $LAST_STATUS"; cat "$WORK/body"; exit 1; }
MERCHANT=$(field "$LAST_BODY" merchant.id)

say "## 2. Signing in"
say ""
say "An access token lasts fifteen minutes and a refresh token thirty days. Send the access token as a"
say "bearer token on every request below. Refresh tokens are single use: presenting a spent one revokes"
say "the whole sign in, because that is what a stolen token looks like."
say ""
call POST /api/v1/tokens "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
TOKEN=$(field "$LAST_BODY" accessToken)
AUTH="Authorization: Bearer $TOKEN"

say "## 3. Opening the account the money is owed into"
say ""
say "The ledger opens nothing on anybody's behalf. Until this account exists, a capture has nowhere to"
say "credit and is refused — which is a better failure than money landing in an account nobody chose."
say ""
call POST "/api/v1/merchants/$MERCHANT/accounts" \
    '{"code":"settlement.try","name":"Owed to the merchant, TRY","type":"LIABILITY","currency":"TRY"}' \
    "$AUTH" "Idempotency-Key: open-settlement-$RUN"

say "## 4. Creating a payment"
say ""
say "A payment is created before any card is involved. \`reference\` is yours, it identifies one payment"
say "for this merchant, and reusing it is refused: that is how a retry that lost its answer cannot become"
say "a second payment."
say ""
say "Amounts are minor units and an ISO 4217 code. 125000 TRY is 1,250.00 lira. There is no floating"
say "point anywhere near money on this platform."
say ""
call POST "/api/v1/merchants/$MERCHANT/payments" \
    "{\"amount\":125000,\"currency\":\"TRY\",\"reference\":\"order-$RUN\",\"description\":\"Two tickets\"}" \
    "$AUTH" "Idempotency-Key: create-payment-$RUN"
PAYMENT=$(field "$LAST_BODY" id)

say "## 5. Authorizing the card"
say ""
say "This reserves the money with the acquirer and posts nothing to the books: an authorization is a"
say "promise, not a movement. \`allowedNext\` says what may happen to the payment from here."
say ""
say "What the acquirer does is decided by the last four digits of the card, so every outcome can be"
say "provoked without the platform knowing it is talking to a simulator. \`0002\` declines for"
say "insufficient funds, \`0069\` approves but withholds the answer for longer than the caller waits, and"
say "anything else approves."
say ""
call POST "/api/v1/merchants/$MERCHANT/payments/$PAYMENT/authorize" \
    '{"card":"4000000000000000"}' \
    "$AUTH" "Idempotency-Key: authorize-payment-$RUN"

say "## 6. The same key, a different request"
say ""
say "Reusing an idempotency key for a request that is not the one it belongs to is refused with 409 and"
say "\`IDEMPOTENCY_KEY_REUSED\`. Nothing is done. This is the check that makes a key safe to retry with:"
say "a key can only ever mean one request."
say ""
call POST "/api/v1/merchants/$MERCHANT/payments" \
    "{\"amount\":999,\"currency\":\"TRY\",\"reference\":\"another-$RUN\"}" \
    "$AUTH" "Idempotency-Key: create-payment-$RUN"

say "## 7. Capturing"
say ""
say "Capturing takes the money and writes the entry. This is the movement: the books gain a balanced"
say "journal entry, and \`ledgerEntryId\` on the payment names it."
say ""
call POST "/api/v1/merchants/$MERCHANT/payments/$PAYMENT/capture" - \
    "$AUTH" "Idempotency-Key: capture-payment-$RUN"

say "## 8. What an error looks like"
say ""
say "Every failure is an RFC 9457 problem detail with a stable \`code\`, a sentence a person can act on,"
say "and the correlation id of the request. Capturing a payment that is already captured is not a"
say "transport failure and does not pretend to be one."
say ""
call POST "/api/v1/merchants/$MERCHANT/payments/$PAYMENT/capture" - \
    "$AUTH" "Idempotency-Key: capture-again-$RUN"

say "## 9. Refunding part of it"
say ""
say "A refund is a new movement that names the capture it reverses, never an edit of it. The payment"
say "keeps \`refundedAmount\` and \`refundableAmount\` so you never have to work out what is left."
say ""
call POST "/api/v1/merchants/$MERCHANT/payments/$PAYMENT/refunds" \
    '{"amount":25000,"currency":"TRY","reason":"one ticket returned"}' \
    "$AUTH" "Idempotency-Key: refund-payment-$RUN"

say "## 10. The payment, afterwards"
say ""
say "Reading it back shows the history: every state it was in, when, and why. Deriving that from the"
say "current status is not possible, so it is recorded rather than reconstructed."
say ""
call GET "/api/v1/merchants/$MERCHANT/payments/$PAYMENT" - "$AUTH"

say "## Rate limiting"
say ""
say "Each merchant gets 100 requests a second with a burst of 200, counted in Redis across every gateway"
say "instance. Over that, the answer is 429 with \`RATE_LIMITED\` and a \`Retry-After\` in seconds:"
say ""
say '```json'
say '{'
say '  "type": "https://mizan.dev/problems/rate-limited",'
say '  "title": "Too Many Requests",'
say '  "status": 429,'
say '  "code": "RATE_LIMITED",'
say '  "detail": "This merchant is sending more than 100 requests a second. Nothing was done. Wait the number of seconds in Retry-After and send it again.",'
say '  "correlationId": "01JBQ9Z0000000000000000000"'
say '}'
say '```'
say ""
say "**Nothing was done** is the part to build on: a rate limited request never reached a service, so"
say "sending it again after \`Retry-After\` is safe, and sending it again with the same idempotency key is"
say "safe whether or not it was ever done. This is not provoked in this document, because a hundred a"
say "second against a laptop proves nothing about a deployment; the limit itself is tested in"
say "\`MerchantRateLimitTest\` and measured under load in [docs/performance](../performance)."
say ""
say "## What to read next"
say ""
say "- The full API: every service's OpenAPI specification is in [docs/api](.), and the stack serves"
say "  <http://localhost:8080/swagger-ui.html> with all of them in one place."
say "- Why it works this way: the decisions are indexed in [docs/adr](../adr/README.md)."

# ---------------------------------------------------------------------------------------------------
# Put the run-specific parts back to placeholders, so two runs of this produce the same document.
"$PYTHON" - "$OUT" "$MERCHANT" "$PAYMENT" "$RUN" "$EMAIL" <<'PY'
import re
import sys

path, merchant, payment, run, email = sys.argv[1:6]
with open(path, encoding="utf-8") as handle:
    text = handle.read()

# One pass over the ids: the two that matter keep a placeholder of their own, and everything else
# (users, roles, refunds, entries, correlation ids) becomes the same anonymous one. Done together,
# because replacing them one after another would let the catch-all overwrite the specific ones.
known = {
    merchant: "11111111-1111-1111-1111-111111111111",
    payment: "22222222-2222-2222-2222-222222222222",
}
text = re.sub(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    lambda found: known.get(found.group(0), "33333333-3333-3333-3333-333333333333"),
    text,
)
text = text.replace(email, "ada@worked-example.test")
text = text.replace(run, "1")
text = re.sub(r"\d{4}-\d{2}-\d{2}T[\d:.]+Z", "2026-01-01T00:00:00Z", text)
text = re.sub(r'"(accessToken|refreshToken)": "[^"]+"', r'"\1": "<a signed token>"', text)
# The token on every request, which is a new one each run and a hundred lines of noise in a diff.
text = re.sub(r"^Authorization: Bearer .*$", "Authorization: Bearer <an access token>", text, flags=re.M)
text = re.sub(r'"acquirerReference": "[^"]+"', '"acquirerReference": "auth_000000000000"', text)
text = re.sub(r'"(correlationId|traceId)": "[0-9a-zA-Z]+"', r'"\1": "0123456789abcdef0123456789abcdef"', text)
text = re.sub(r"^x-correlation-id: .*$", "x-correlation-id: 0123456789abcdef0123456789abcdef", text,
              flags=re.M | re.I)
text = re.sub(r"^location: (.*)$", r"location: \1", text, flags=re.M | re.I)

with open(path, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(text)
PY

if [ "$MODE" = "--check" ]; then
    if ! diff -u "$DOCUMENT" "$OUT" > "$WORK/diff"; then
        echo "docs/api/worked-example.md is not what the platform answers now:" >&2
        head -60 "$WORK/diff" >&2
        echo >&2
        echo "Run ./scripts/worked-example.sh against a running stack and commit the result." >&2
        exit 1
    fi
    echo "the worked example is what the platform answers"
else
    cp "$OUT" "$DOCUMENT"
    echo "docs/api/worked-example.md written from a real run"
fi
