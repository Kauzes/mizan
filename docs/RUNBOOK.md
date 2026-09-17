# Runbook

For whoever is holding the pager. Every command here was run against the platform while this was
written, and its output is what the command printed.

Nothing here edits money by hand. Where a correction is needed, it goes through the ledger as an entry
like any other — a platform where an operator can fix the books with an UPDATE is a platform whose books
mean nothing.

## Starting and stopping

```sh
docker compose up -d --wait     # starts everything and waits until each container is healthy
docker compose ps               # what is running
docker compose stop             # stop, keeping the data
docker compose down             # stop and remove the containers, keeping the volumes
docker compose down -v          # and throw the data away
```

`--wait` is the part that matters: without it the command returns while Postgres is still starting and
the first thing you try fails for a reason that has nothing to do with what you were testing.

## What healthy looks like

Four questions, in the order worth asking them.

**Is everything up?**

```sh
docker compose ps --format "table {{.Service}}\t{{.Status}}"
```

```
SERVICE                STATUS
bank-simulator         Up 6 hours (healthy)
console                Up 6 hours (healthy)
gateway                Up 6 hours (healthy)
grafana                Up 6 hours (healthy)
identity-service       Up 6 hours (healthy)
```

Every row should say `(healthy)`. A container that is `Up` without `(healthy)` is still starting or
failing its own healthcheck, and the next question says which.

**Does the edge answer?**

```sh
curl -s localhost:8080/actuator/health
```

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

**Is anything already firing?**

```sh
curl -s localhost:9090/api/v1/alerts |
  python -c 'import json,sys; [print(a["labels"]["alertname"], a["state"]) for a in json.load(sys.stdin)["data"]["alerts"]]'
```

No output means nothing is firing. While this was written, two were:

```
AnEventWasSetAside firing
TheBankDisagreesAndNobodyHasRuled firing
```

Both are worked through below.

**Does the whole journey still work?**

```sh
./scripts/smoke.sh
```

It registers its own merchant, takes a payment, refunds it, and checks twenty-four things the test suite
structurally cannot — real services, in the images they are deployed as, reached through the gateway. It
ends with the alert rules being loaded and the ledger balancing. If this passes, the platform works; if
it fails, it says which step and why.

## The alerts

Six rules, in `deploy/local/alerts.yml`. Each one exists because somebody has to do something.

### LedgerDoesNotBalance — page

The books are the product. This fires on a single scrape, because a drifted ledger does not fix itself.

**Confirm:**

```sh
curl -s localhost:8082/actuator/ledgerintegrity
```

```json
{"sound":true,"summary":"the ledger balances in every currency and every balance agrees with its
postings, over 24758 entries and 49516 postings","checkedAt":"2026-09-17T17:51:56Z","tookMillis":82}
```

**Act:** stop settlement payouts first, then read which of the three questions failed — an entry that
does not sum to zero, an account whose balance disagrees with its postings, or a currency that does not
balance across the platform. That says where the bug is. Correct it with a new entry that names what it
corrects. Never edit a balance.

### AnEventWasSetAside — page

An event that could not be handled is something a merchant should have been told and was not. See
[the dead letters](#the-dead-letters) below, which is where it is diagnosed and cleared.

### EventsHaveStoppedLeaving — page

Payments are still being taken, which is why this needs an alert: the outbox keeps a broker outage from
failing a single payment, so nothing a merchant does looks wrong. What stops is everything downstream.

**Confirm:**

```sh
for metric in mizan_outbox_waiting mizan_outbox_oldest_waiting_seconds; do
  printf '%-40s' "$metric"
  curl -s --get localhost:9090/api/v1/query --data-urlencode "query=max($metric)" |
    python -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "no data")'
done
```

```
mizan_outbox_waiting                    0
mizan_outbox_oldest_waiting_seconds     0
```

Healthy is a waiting count near zero and an oldest age of a second or two. A rising oldest age with a
rising count is nothing leaving.

**Act:** check whether Kafka is up and whether payment-service can reach it —
`docker compose ps kafka` and `docker compose logs --tail 200 payment-service | grep -i relay`, whose
warnings name the error. Nothing is lost while it waits: the events are in the outbox and go out in
order once the broker answers. Do not delete or edit outbox rows. `./scripts/chaos-kafka.sh` reproduces
this deliberately if you want to see it recover.

### TheBankDisagreesAndNobodyHasRuled — ticket

A difference between what this platform thinks it settled and what the bank's statement says, older than
a day, that nobody has ruled on.

**Confirm:**

```sh
curl -s localhost:8087/actuator/reconciliation |
  python -c 'import json,sys; d=json.load(sys.stdin); print(d["outstanding"], "outstanding, oldest", d["oldest"], d["byOutcome"])'
```

```
42 outstanding, oldest 2026-09-12T17:35:47Z {'AMOUNTS_DIFFER': 19, 'EXTRA_ON_STATEMENT': 3, 'MISSING_FROM_STATEMENT': 20}
```

**Act:** take the oldest, compare it with the bank's statement, and rule on it — `ACKNOWLEDGED` when it
is explained, or corrected with the id of the ledger entry that corrects it:

```sh
curl -s -X POST localhost:8087/actuator/reconciliation/differences/<difference-id> \
  -H 'Content-Type: application/json' \
  -d '{"ruling":"ACKNOWLEDGED","ruledBy":"your name","why":"what you found"}'
```

```json
{"difference":"05c91071-0066-48ee-bc33-c10579a77d6a","ruling":"ACKNOWLEDGED","ruledBy":"claude",
 "ruledAt":"2026-09-17T17:46:58Z","correctedBy":null,
 "changed":"nothing; this difference is explained rather than undone"}
```

Note what it says it changed: nothing. A ruling is a record of a decision, not a movement of money. If
money has to move, post the entry in the ledger and name it here with `correctedBy`.

### AuthorizationsCollapsed — page

Fewer than half of authorizations approved, over ten minutes, with at least twenty attempted. The shape
of an acquirer outage and of a bad deployment.

**Confirm:**

```sh
curl -s --get localhost:9090/api/v1/query --data-urlencode \
  'query=sum by (outcome) (increase(mizan_payments_authorizations_total[10m]))'
```

**Act:** look at "why the bank refused" on the *is it just this payment* dashboard in Grafana
(localhost:3000). One decline reason dominating means the acquirer — call them. Everything rising at
once just after a release means the release: roll it back first and investigate second.

### AServiceIsNotAnswering — page

**Confirm:**

```sh
curl -s --get localhost:9090/api/v1/query --data-urlencode 'query=up{job="mizan"} == 0' |
  python -c 'import json,sys; [print(r["metric"].get("service","?")) for r in json.load(sys.stdin)["data"]["result"]]'
docker compose ps
```

**Act:** read that container's logs and its readiness endpoint. A service that cannot reach its database
reports itself *not ready* rather than down, so `up == 0` means the process itself is gone:

```sh
docker compose logs --tail 100 <service>
curl -s localhost:<port>/actuator/health/readiness
```

```json
{"status":"UP"}
```

The ports are in the README's service table. Two minutes of downtime does not page, so a rolling restart
passes through this state without waking anybody.

## The dead letters

An event that a handler could not process, after its retries, is set aside rather than dropped. It is
the only record that a merchant was never told something.

**Look at what is outstanding:**

```sh
curl -s localhost:8085/actuator/deadletters |
  python -c 'import json,sys; d=json.load(sys.stdin); print(d["outstanding"], "outstanding"); print(d["byHandler"])'
```

```
1 outstanding
[{'handler': 'payment-notifications', 'type': 'payment.captured', 'outstanding': 1}]
```

It is on the service's own port, not through the gateway: a dead letter is an operator's problem, not a
merchant's, so it is an actuator endpoint and the gateway does not route it.

**Read the oldest one — the `reason` is the whole diagnosis:**

```sh
curl -s localhost:8085/actuator/deadletters |
  python -c 'import json,sys; l=json.load(sys.stdin)["letters"][0]; print(l["id"]); print(l["type"], l["eventId"]); print(l["reason"]); print("attempts:", l["attempts"], "first failed:", l["firstFailedAt"])'
```

```
7bb59ddb-7f09-492a-aa2e-3d5598c50d7b
payment.captured 4269f8a5-9726-474c-870c-95cfebad6551
ListenerExecutionFailedException: ... The input currency code: "NOTACURRENCY" must have a length of 3 characters
attempts: 2 first failed: 2026-09-02T21:43:41Z
```

**Fix what the reason names. Then redeliver it:**

```sh
curl -s -X POST localhost:8085/actuator/deadletters/<dead-letter-id> -H 'Content-Type: application/json'
```

```json
{"redelivered":"4269f8a5-9726-474c-870c-95cfebad6551","to":"mizan.payment.events","afterFailures":2}
```

It is republished to the topic it came from, under its original key, so it arrives exactly as an ordinary
delivery does and goes through the same inbox. That makes redelivery safe either way: if the event was
already handled, the inbox finds its own record and does nothing; if it was not, it is handled once.

**Then check it actually cleared:**

```sh
sleep 10
curl -s localhost:8085/actuator/deadletters | python -c 'import json,sys; print(json.load(sys.stdin)["outstanding"], "outstanding")'
docker compose logs --tail 50 notification-service | grep -i "dead letter"
```

When this was written, redelivering **without** fixing the cause did exactly what it should:

```
1 outstanding
DEAD LETTER: payment.captured 4269f8a5-...  could not be handled by payment-notifications and has been
set aside: ... The input currency code: "NOTACURRENCY" must have a length of 3 characters
```

The old record is marked redelivered and a new one takes its place, with the failure count going up. So
"the queue is empty" immediately after a redelivery means the event is in flight, not that it worked:
wait, then look again.

**A dead letter whose cause cannot be fixed** — a poisoned payload from a test, an event naming
something that never existed — is closed instead, with your name and a reason:

```sh
curl -s -X POST localhost:8085/actuator/deadletters/<dead-letter-id>/close \
  -H 'Content-Type: application/json' \
  -d '{"closedBy":"your name","why":"what you found, and why nothing more will be done"}'
```

```json
{"closed":"4269f8a5-9726-474c-870c-95cfebad6551","closedBy":"claude","afterFailures":3,
 "why":"left by the MIZ-53 live check on 2026-09-02: the payload carries the currency NOTACURRENCY, so no
        version of this service could ever have handled it",
 "kept":"the letter, its reason and its payload stay readable"}
```

Both are required, and a closing without either is refused — a decision nobody can account for later is
not a decision, it is a disappearance. **Closing is not deleting**: the row, its reason and its payload
stay exactly where they were, because they are the only record that a merchant was never told something.
What changes is that it stops counting as outstanding, so `AnEventWasSetAside` can clear.

If the same event fails again afterwards, it comes back, outstanding, with its failure count raised.
Closing speaks about what was set aside, not about the future.

## Payments that need a person

None of these page. They are the queues an operator drains.

**A payment whose authorization nobody knows the answer to** (the acquirer timed out and the sweep has
not resolved it):

```sh
docker exec mizan-postgres-1 psql -U mizan -d payment -c \
  "select id, amount, currency, created_at from payment where status = 'AUTHORIZATION_UNKNOWN'"
```

```
                  id                  | amount | currency |          created_at
--------------------------------------+--------+----------+-------------------------------
 560aeb91-31df-4a98-afa7-7c05a455e172 | 125000 | TRY      | 2026-09-06 09:55:06.746752+00
```

The platform resolves these itself by asking the acquirer again, so one appearing for a few minutes is
the system working. One that is hours old means the acquirer never gave an answer either. Look for a
decision somebody already recorded before you do anything:

```sh
docker exec mizan-postgres-1 psql -U mizan -d payment -c \
  "select decision, decided_by, why, at from operator_decision where subject_id = '<payment-id>' order by at"
```

**A capture that began and did not finish:**

```sh
docker exec mizan-postgres-1 psql -U mizan -d payment -Atc \
  "select count(*) from payment where capture_started_at is not null and status = 'AUTHORIZED'"
```

```
0
```

Zero is what this should be. A payment that wrote down that its capture began and is still only
authorized was interrupted between the two; the resolver sweep finishes it. Anything still here after a
few minutes wants reading rather than retrying — the money may already be taken.

**Payments waiting for a human ruling:**

```sh
docker exec mizan-postgres-1 psql -U mizan -d payment -Atc \
  "select count(*) from payment where status = 'HELD_FOR_REVIEW' and review_ruling is null"
```

```
10
```

These are ruled on in the console's review queue, or on the phone (both use the same endpoints). Nobody
has been charged for any of them. A growing queue is a risk threshold set too tight, not an incident.

## The rate limit

A merchant sending more than 100 requests a second (burst 200) is refused with 429 and a `Retry-After`.
This is per merchant, counted in Redis, so it holds across gateway instances.

**Confirm a merchant is actually being limited**, rather than something else returning 429:

```sh
curl -s --parallel --parallel-max 120 -H "Authorization: Bearer $TOKEN" -w '%{http_code}\n' -K urls.txt |
  sort | uniq -c | sort -rn
```

```
    400 429
    200 200
```

with `urls.txt` holding the same request 600 times (`url = "..."` and `output = "/dev/null"` per line).
The body of a refusal says what it is and what to do:

```json
{"code":"RATE_LIMITED","status":429,
 "detail":"This merchant is sending more than 100 requests a second. Nothing was done. Wait the number
           of seconds in Retry-After and send it again.",
 "correlationId":"008e0f32-feac-43ab-92e8-699d2284d591"}
```

**Act:** *nothing was done* is the important part — a refused request never reached a service, so the
client can retry after `Retry-After`, and retrying with the same idempotency key is safe whether or not
the request was ever carried out. If a legitimate merchant is hitting the limit, raise it for the
platform with `MIZAN_RATE_LIMIT_PER_SECOND` and `MIZAN_RATE_LIMIT_BURST`; if Redis is unreachable the
limiter fails open, which is visible as the limit not applying rather than as an outage.

## Reproducing failures on purpose

Three scripts break the platform deliberately, so that what an incident looks like can be seen before it
happens for real:

```sh
./scripts/chaos-kafka.sh     # cuts the broker off under load; payments keep being taken, events queue
./scripts/chaos-ledger.sh    # stops the ledger mid capture; nothing is lost and the sweep finishes it
```

Both were run while this was written. The last lines they printed:

```
✓ Kafka was cut off under load, payments kept being taken, and every event arrived once
✓ the ledger died mid-capture under load, and no payment was lost
```

`./scripts/rollout-under-load.sh` does the same for a rolling restart, and needs a kind cluster
(`./scripts/autoscaling-on-kind.sh` builds one); it is the only script here that was not run against
this Compose stack, because it is not about this Compose stack.

## Where to look next

- **Grafana**, localhost:3000 — two dashboards, provisioned: *how business is* and *is it just this
  payment*.
- **Traces**, through Grafana or directly from Tempo on localhost:3200. Every log line carries both the
  correlation id a person reads out and the trace id that opens the trace.
- **The decisions**, [docs/adr](adr/README.md) — why any of this works the way it does.
