# ADR 0059: A phone payment is written down before it is sent, and never with the card

- Status: accepted
- Date: 2026-09-16
- Jira: MIZ-99
- Narrowed: 2026-09-17 by ADR 0060, which lets a payment queued with no signal keep its card, encrypted and for at most a day, until its authorization is answered.

## Context

A merchant takes a card payment on the phone: create the payment, authorize the card, capture. A phone is
the least reliable place in the platform to run that sequence from. The app can be killed by the system,
swiped away, or lose signal between any two steps, and between sending a request and hearing its answer.
Three facts about the platform shape what the phone has to do:

- **Every write accepts an `Idempotency-Key`** (ADR 0017). The same key with
  the same body is answered with what was already done; the same key with a different body is refused
  with 409 `IDEMPOTENCY_KEY_REUSED`; a request that failed releases its key so it can be sent again.
- **An authorization or capture that timed out at the acquirer answers 504**, and the payment may still
  have moved (`AUTHORIZATION_UNKNOWN`, resolved by asking the acquirer, ADR 0020 and ADR 0055).
- **A merchant's reference identifies one payment** (`payment_reference_per_merchant`).

The failure this story exists to prevent is the phone forgetting it started a payment, or remembering it
without the keys that make continuing safe, and so taking the customer's money twice.

## Decision

**A payment is written to a Room database, with its reference and one idempotency key for each of create,
authorize and capture, before the first request is sent. Every answer is written down before the next
step. Resuming sends the same requests with the same keys, from the last step written. The card number is
never written down.**

- **Keys chosen up front, per step.** Three keys, not one, because the platform scopes a key to its
  endpoint, and a single key reused across endpoints would say nothing about which step a retry belongs to.
- **A step that may have happened is confirmed, not repeated.** A 504 moves the payment to *confirming*;
  the platform is asked what the payment is now. `CREATED` means nothing reached the acquirer and the
  authorization is sent again. `AUTHORIZATION_UNKNOWN` waits. Anything else is the answer.
- **A 422 on authorize or capture is read as "already moved"**, which is what it means when an earlier
  try's answer was lost: the payment is read back rather than the attempt called failed. A capture the
  books refuse reads back as still authorized, so the two steps would hand the payment to each other; the
  state machine is bounded at eight steps a pass and stops with the platform's own reason.
- **Busy is not refused.** 429 and 409 `CONTENDED` leave the payment unfinished, to be continued; any other
  refusal finishes it as refused, with the platform's reason.
- **The card is never kept.** The platform keeps a card out of even its own logs (ADR 0044), and the phone keeps it out of storage. It is held in memory for one authorization and cleared from the screen's
  state when it is handed over. A payment interrupted before its authorization was answered asks for the
  card again. The same card makes the same request, which the platform replays or reads back; a different
  card is a different body under the same key, which the platform refuses, and the app says so and that
  nothing was charged to that card.
- **Unfinished payments are offered to continue** whenever the take-payment screen is open, including ones
  from before the app was last killed. Nothing continues on its own: a customer who has walked away should
  not be charged by a till that came back to life.
- **No destructive migration.** Unlike Sentinel Pay's database, a schema change here comes with a
  migration: dropping the table on upgrade would lose exactly the keys this decision depends on.
- **Room 2.8.4 with KSP 2.3.11**, the Sentinel Pay versions, so the two apps stay one toolchain.

## Evidence

**Unit tests, 14.** `PaymentTakerTest` (9) against a fake platform that honours idempotency keys the way
the real one does: a normal payment; every answer lost and the step sent again; **the app killed after
each step and relaunched, charged once**; a 504 read back as authorized and captured; held for review; a
decline; a different card refused under the same key; a 503 retried with the same key; unfinished payments
found after a relaunch. `MoneyInputTest` (5): amounts typed with `.` or `,`, and anything that is not an
amount refused rather than guessed.

**On an Android 16 emulator, 18** (7 new). `RoomAttemptStoreTest` (4) against the real SQLite: an attempt
comes back exactly as written; saving again replaces it; only unfinished attempts are offered; no run of
twelve or more digits anywhere in the table. `TakePaymentScreenTest` (3).

**Driven on the emulator, against the local Compose stack**, with a merchant registered through the gateway:

| | |
|---|---|
| 125.50 TRY, approving card | "Paid: 125.50 TRY, card ending 0000."; the platform held one `CAPTURED` payment |
| 77.00 TRY on the slow card (`…0069`) | app force-stopped 3 seconds after Take payment, while the acquirer withheld its answer |
| At the kill | the platform held the payment as `CREATED`; its sweep later moved it `AUTHORIZATION_UNKNOWN` → `AUTHORIZED` |
| App reopened | "Continue 77.00 TRY" offered; continuing asked for the card again |
| Card entered | the authorize was answered 422 (already moved), read back as authorized, captured: "Paid: 77.00 TRY, card ending 0069." |
| Platform, for that payment | **1 payment; transitions CREATED, AUTHORIZATION_UNKNOWN, AUTHORIZED ×1, CAPTURED ×1** |
| Every file in the app's storage (149,650 bytes: database, WAL, preferences) | the payment references present; **no card number** |

The first run of that drive found a gap in the drive rather than the app: a newly registered merchant has
no `settlement.try` account, and the books refused the capture. The phone showed the ledger's reason and
kept the payment unfinished, still `AUTHORIZED` on the platform, rather than calling it paid or failed.

## Consequences

- **An interrupted payment needs the merchant to act**, and sometimes the card again. That is slower than
  resuming silently, and is the price of not storing a card and not charging a customer who has left.
- **The phone's record is not the platform's.** It says what this phone last knew; the platform's payment
  is the truth, and continuing always asks it. A payment finished from the console is found finished the
  next time the phone continues it.
- **An unfinished payment stays listed until it is continued.** Nothing expires it on the phone yet; the
  offline queue (MIZ-100) extends this table and decides what an old unfinished payment becomes.
- **Idempotency records on the platform never expire** (a follow-up already noted), which is what makes a
  phone that comes back days later safe. If they ever expire, this decision needs a maximum age.

## Alternatives

- **One idempotency key for the whole payment.** The platform scopes keys per endpoint, so it would work
  mechanically, but a retry could no longer say which step it belongs to.
- **Storing the card, encrypted, until the payment finishes.** Resumes without asking, and makes the till
  hold card numbers: PCI scope for a convenience. Rejected.
- **Resuming automatically on launch.** Charges a customer who may have walked away and paid another way.
- **DataStore instead of Room.** A list of records queried by step, which the offline queue will extend,
  is a table.
- **WorkManager for the sequence.** Survives process death by itself, but the card cannot be handed to a
  worker without persisting it, which is the one thing this must not do.
