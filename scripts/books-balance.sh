#!/usr/bin/env bash
#
# The invariant the whole platform exists to hold, asked of everything that has ever been
# written rather than of one entry at a time.
#
# Three questions, and which one fails says where the bug is: every entry balances on its own,
# every account's balance is what its postings add up to, and every currency sums to zero
# platform wide — including the clearing and revenue accounts no merchant ever sees. The third
# is the one worth having: if the first two pass and it fails, this platform is holding or
# owing money it has not written down.
#
# Run on its own, and run by the smoke check as its last step. On its own because CI runs it
# again after the browser journey and the demo seed, over data that three different things
# produced and none of them wrote to make this pass.

set -euo pipefail

. "$(dirname "$0")/mizan.sh"

step "The whole ledger balances"

books=$(call 200 GET "$LEDGER/actuator/ledgerintegrity")

examined() { printf '%s' "$books" | field "examined.$1"; }

entries=$(examined entries)
postings=$(examined postings)
accounts=$(examined accounts)

# Nothing here may be satisfied by an empty database. Every one of the three questions passes
# trivially over empty tables, so the first thing to establish is that there was a ledger.
[ "${entries:-0}" -ge 1 ]  || fail "the ledger holds no entries, so this proves nothing: $books"
[ "${postings:-0}" -ge 2 ] || fail "the ledger holds no postings, so this proves nothing"
[ "${accounts:-0}" -ge 1 ] || fail "the ledger holds no accounts, so this proves nothing"
pass "$entries entries, $postings postings and $accounts accounts were read"

unbalanced=$(printf '%s' "$books" | "$PYTHON" -c '
import json, sys
report = json.load(sys.stdin)
for entry in report["entries"]:
    print("  entry %s (%s) has %d posting(s) in %s summing to %d rather than 0"
          % (entry["reference"], entry["entryId"], entry["postings"],
             entry["currency"], entry["outBy"]))
print(len(report["entries"]))' | tail -1)
[ "$unbalanced" = "0" ] || fail "$unbalanced entry/entries do not balance on their own"
pass "every entry's own postings sum to zero, in every currency it touches"

drifted=$(printf '%s' "$books" | "$PYTHON" -c '
import json, sys
report = json.load(sys.stdin)
for account in report["drifted"]:
    print("  account %s holds %d but its postings sum to %d, out by %d"
          % (account["code"], account["keptBalance"], account["postingsTotal"],
             account["outBy"]))
print(len(report["drifted"]))' | tail -1)
[ "$drifted" = "0" ] || fail "$drifted account(s) disagree with their own postings"
pass "every account's balance is exactly what its postings add up to"

printf '%s' "$books" | "$PYTHON" -c '
import json, sys
for total in json.load(sys.stdin)["totals"]:
    print("  %s: %d posting(s) across %d account(s), summing to %d"
          % (total["currency"], total["postings"], total["accounts"], total["total"]))' \
    | while read -r line; do note "${line# }"; done

currencies=$(printf '%s' "$books" | "$PYTHON" -c '
import json, sys
totals = json.load(sys.stdin)["totals"]
print(len([t for t in totals if t["total"] != 0]))')
[ "$currencies" = "0" ] || fail "$currencies currency/currencies do not sum to zero"

sound=$(printf '%s' "$books" | field sound)
[ "$sound" = "True" ] || [ "$sound" = "true" ] || {
    printf '%s\n' "$books" >&2
    fail "the ledger says it has drifted: $(printf '%s' "$books" | field summary)"
}
pass "every currency sums to zero platform wide, the merchants' books and the platform's"

# Said out loud rather than left in a report nobody opens. A check whose cost nobody watches
# is a check that is switched off the first time somebody notices it, and the day this takes
# long enough to be worth reading about is the day to move it off the write path.
note "checked in $(printf '%s' "$books" | field tookMillis)ms"
