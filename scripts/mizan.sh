#!/usr/bin/env bash
#
# What the smoke and seed scripts both need: talking to the platform, reading its answers,
# and saying what happened.
#
# Only curl and Python are assumed. Both are on a CI runner and on a laptop with Git Bash,
# and jq is neither. Nothing here parses JSON with a regular expression, which is the usual
# way a shell script comes to believe a 422 was a success.

set -euo pipefail

GATEWAY="${MIZAN_GATEWAY:-http://localhost:8080}"
LEDGER="${MIZAN_LEDGER:-http://localhost:8082}"

# Whichever Python is here, chosen by running it rather than by finding it on PATH. Windows
# ships a python3 that is not an interpreter at all but a stub that advertises the Microsoft
# Store, and it answers `command -v` perfectly happily.
PYTHON=""
for candidate in python3 python py; do
    if command -v "$candidate" > /dev/null 2>&1 &&
        "$candidate" -c 'import json,sys' > /dev/null 2>&1; then
        PYTHON="$candidate"
        break
    fi
done
if [ -z "$PYTHON" ]; then
    echo "This script needs a working python3 (or python) to read JSON. Nothing else." >&2
    exit 1
fi

if ! command -v curl > /dev/null; then
    echo "This script needs curl." >&2
    exit 1
fi

# -- saying what happened -------------------------------------------------------------------

# Colour only when something is watching. A log file full of escape codes helps nobody.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; OFF=$'\033[0m'
else
    BOLD=""; GREEN=""; RED=""; DIM=""; OFF=""
fi

step() { printf '\n%s%s%s\n' "$BOLD" "$1" "$OFF"; }
note() { printf '  %s%s%s\n' "$DIM" "$1" "$OFF"; }
pass() { printf '  %s✓%s %s\n' "$GREEN" "$OFF" "$1"; }
fail() { printf '  %s✗ %s%s\n' "$RED" "$1" "$OFF" >&2; exit 1; }

# -- reading answers ------------------------------------------------------------------------

# Pulls one value out of a JSON document by path, e.g. `field merchant.id`. Missing is fatal:
# a script that carries on with an empty id reports its failure three steps after the cause.
field() {
    "$PYTHON" -c '
import json, sys
document = json.load(sys.stdin)
for name in sys.argv[1].split("."):
    if isinstance(document, list):
        document = document[int(name)]
    else:
        if name not in document:
            sys.exit("no " + sys.argv[1] + " in " + json.dumps(document)[:400])
        document = document[name]
print("" if document is None else document)
' "$1"
}

count() { "$PYTHON" -c 'import json,sys; print(len(json.load(sys.stdin)))'; }

key() { "$PYTHON" -c 'import uuid; print(uuid.uuid4())'; }

# -- talking to the platform ----------------------------------------------------------------

# Calls the platform and insists on the status it was told to expect. The response body goes
# to stdout; everything else goes to stderr, so `body=$(call ...)` stays clean.
#
#   call <expected status> <method> <url> [body] [extra curl args...]
call() {
    local expected="$1" method="$2" url="$3" body="${4:-}"
    shift 4 2>/dev/null || shift 3

    local response status payload
    if [ -n "$body" ]; then
        response=$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" \
            -H 'Content-Type: application/json' -d "$body" "$@")
    else
        response=$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" "$@")
    fi

    status="${response##*$'\n'}"
    payload="${response%$'\n'*}"

    if [ "$status" != "$expected" ]; then
        printf '  %s✗ %s %s answered %s, expected %s%s\n' \
            "$RED" "$method" "$url" "$status" "$expected" "$OFF" >&2
        printf '    %s\n' "$payload" >&2
        exit 1
    fi
    printf '%s' "$payload"
}

# The same, with a merchant's access token. AUTH is set once a merchant has signed in.
authed() {
    local expected="$1" method="$2" url="$3" body="${4:-}"
    call "$expected" "$method" "$url" "$body" \
        -H "Authorization: Bearer $AUTH" -H "Idempotency-Key: $(key)"
}
