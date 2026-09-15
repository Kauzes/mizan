"""Takes payments through the gateway for a fixed time, and says what happened to every request.

Runs as a pod inside the cluster (scripts/rollout-under-load.sh), so requests reach the gateway through
its Service the way real clients do, while the platform is rolled out underneath them.

What counts as a failed request is decided here and nowhere else, before any run:

  * no response at all: a reset, a refused connection, a timeout
  * a 5xx
  * a 4xx on a request that was valid for the state it was sent in

A payment that risk holds for review, or the acquirer declines, is the platform working. It is not
captured, because capturing it would be the invalid request, and it is not a failure.

Standard library only, so the image is a plain Python and nothing is installed at runtime.
"""

import json
import os
import threading
import time
import urllib.error
import urllib.request
import uuid

GATEWAY = os.environ.get("GATEWAY", "http://gateway:8080")
LEDGER = os.environ.get("LEDGER", "http://ledger-service:8082")
SECONDS = int(os.environ.get("SECONDS", "300"))
WORKERS = int(os.environ.get("WORKERS", "6"))
TIMEOUT = 15

lock = threading.Lock()
counts = {"requests": 0, "failed": 0, "captured": 0, "held_or_declined": 0}
failures = {}


def call(method, url, body=None, token=None, idempotent=False):
    """Returns (status, parsed body) or (None, error text). Never raises."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if idempotent:
        request.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as refused:
        return refused.code, None
    except Exception as broken:  # a reset, a refusal, a timeout: no answer at all
        return None, type(broken).__name__


def record(step, status, expected):
    """Counts one request, and returns whether it got the answer it should have."""
    ok = status == expected
    with lock:
        counts["requests"] += 1
        if not ok:
            counts["failed"] += 1
            kind = "%s: %s" % (step, status if isinstance(status, int) else "no response (%s)" % status)
            failures[kind] = failures.get(kind, 0) + 1
    return ok


def set_up():
    """One merchant, signed in, with the settlement account a capture posts to."""
    email = "rollout-%s@mizan.local" % uuid.uuid4().hex[:12]
    password = "a-rollout-password-%s" % uuid.uuid4().hex[:8]
    for attempt in range(60):
        status, body = call("POST", GATEWAY + "/api/v1/merchants", {
            "merchantName": "Rollout Test Co",
            "fullName": "Ada Lovelace",
            "email": email,
            "password": password,
        }, idempotent=True)
        if status == 201:
            break
        time.sleep(2)
    else:
        raise SystemExit("could not register a merchant: %s" % status)
    merchant = body["merchant"]["id"]

    status, body = call("POST", GATEWAY + "/api/v1/tokens", {"email": email, "password": password})
    if status != 200:
        raise SystemExit("could not sign in: %s" % status)
    token = body["accessToken"]

    status, _ = call("POST", GATEWAY + "/api/v1/merchants/%s/accounts" % merchant, {
        "code": "settlement.try",
        "name": "Owed to the merchant, TRY",
        "type": "LIABILITY",
        "currency": "TRY",
    }, token=token, idempotent=True)
    if status != 201:
        raise SystemExit("could not open the settlement account: %s" % status)
    return merchant, token


def worker(merchant, token, deadline):
    payments = GATEWAY + "/api/v1/merchants/%s/payments" % merchant
    while time.time() < deadline:
        status, payment = call("POST", payments, {
            "amount": 125000,
            "currency": "TRY",
            "reference": "rollout-%s" % uuid.uuid4().hex,
        }, token=token, idempotent=True)
        if not record("create", status, 201):
            continue

        status, authorized = call("POST", "%s/%s/authorize" % (payments, payment["id"]),
                                  {"card": "4000000000000000"}, token=token, idempotent=True)
        if not record("authorize", status, 200):
            continue
        if authorized.get("status") != "AUTHORIZED":
            with lock:
                counts["held_or_declined"] += 1
            continue

        status, _ = call("POST", "%s/%s/capture" % (payments, payment["id"]),
                         token=token, idempotent=True)
        if record("capture", status, 200):
            with lock:
                counts["captured"] += 1


def books():
    status, report = call("GET", LEDGER + "/actuator/ledgerintegrity")
    if status != 200 or report is None:
        return "unreadable: %s" % status
    return "sound" if report.get("sound") else "drifted: %s" % report.get("summary")


def main():
    merchant, token = set_up()
    print("warmed up: merchant %s signed in" % merchant, flush=True)

    deadline = time.time() + SECONDS
    threads = [threading.Thread(target=worker, args=(merchant, token, deadline), daemon=True)
               for _ in range(WORKERS)]
    for thread in threads:
        thread.start()

    # Requests completed in each ten seconds. The failure count alone rewards a platform that stalls:
    # a request that hangs for fifteen seconds is one failure where a healthy platform would have
    # answered dozens, so a stall barely moves the percentage. The windows are what show it.
    windows = []
    before = 0
    while any(thread.is_alive() for thread in threads):
        time.sleep(10)
        with lock:
            done = counts["requests"]
            windows.append(done - before)
            before = done
            print("progress requests=%d failed=%d captured=%d this_window=%d" % (
                done, counts["failed"], counts["captured"], windows[-1]), flush=True)

    # The outbox and the consumers settle for a moment after the last capture.
    time.sleep(15)
    summary = dict(counts, failures=failures, windows=windows, books=books())
    print("SUMMARY " + json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()
