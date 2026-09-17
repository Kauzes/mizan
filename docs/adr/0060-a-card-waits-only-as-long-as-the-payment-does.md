# ADR 0060: A queued payment keeps its card, encrypted, and only until it is answered

- Status: accepted
- Date: 2026-09-17
- Jira: MIZ-100

## Context

A merchant takes payments where the signal is bad: a market stall, a van, the back of a shop. MIZ-99 made
a payment on the phone safe to interrupt — written down with its idempotency keys before anything is sent,
continued with the same keys — and decided that **the card is never written down**: a payment interrupted
before its authorization was answered asks for the card again.

That decision assumed the interruption is short and the merchant is still holding the card. With no signal
it is neither. The customer has gone. The card cannot be asked for again in an hour, and a till that says
"the payment you took at the stall this morning needs the card again" has not taken a payment at all.

Authorizing needs the card. So either a payment with no signal is refused at the moment it is taken, or
the card waits somewhere until the authorization can be sent. This is the store-and-forward problem every
offline card terminal has, and it does not have a clever answer: something has to hold the card.

## Decision

**A payment that could not be sent keeps its card — encrypted under its own Keystore key, for that one
payment, with an expiry — and the card is forgotten the moment the authorization is answered. Every other
path keeps nothing. This narrows ADR 0059's "the card is never written down" to "the card is never kept
past the authorization it is for".**

- **Only when the platform could not be reached.** The card is kept at exactly one point: a create or an
  authorize that got no answer. A refusal, a decline, a sign-out and an answered authorization all forget
  it first and act second.
- **Its own key, not the session's.** A separate Keystore alias from `KeystoreSessionStore`'s, because the
  two are wiped at different moments for different reasons: signing out must clear the session without
  stranding a queued payment, and a finished payment must forget its card without touching the session.
- **It expires after a day.** Long enough for an evening with no signal, short enough that a card is not
  sitting on a phone for a week. Afterwards the payment is still queued and still charged once; it just
  asks for the card again, which is MIZ-99's behaviour returning when it is affordable again.
- **Sent oldest first, one at a time, under a lock.** The merchant's order is the order the books see, and
  a network that flaps while a screen opens cannot start three passes at once.
- **Nothing is resolved quietly.** A payment the platform refused, a reference it has already seen, a card
  whose keeping expired, and a session that ended while the phone was offline are all handed back to the
  merchant as conflicts. Sync decides one thing on its own: to try again later.
- **Connectivity only explains, never judges.** Coming online starts a pass; whether the platform actually
  answered decides everything. A phone on a network that reaches nothing is the offline case with extra
  steps, and it is handled by the same waiting.

## Evidence

**Unit tests, 7 new** (`PaymentSyncTest`), against a platform that can be unplugged and that honours
idempotency keys: a payment taken offline keeps its card and is sent when signal returns, charged once and
its card forgotten; a queue of three sent oldest first, each charged once; **a sync interrupted halfway**
— the network dropping again mid-queue — finishing the rest on the next pass with two charges for two
payments; a reference the platform already has handed back as a conflict and not retried; a session that
ended while offline stopping the queue with both payments still there; a card kept too long forgotten,
with the payment asking for it again; and a second pass finding nothing left to do.

**On an Android 16 emulator, 25 (7 new).** `KeystoreCardVaultTest` (5) against the real Keystore: a kept card comes
back for its own payment only; what is on disk is not the card and holds no run of twelve or more digits;
forgetting leaves no keys behind; an expired card goes while one still in time stays; tampered ciphertext
is no card and is cleared. Two more on the screen: with no signal it says payments can still be taken and
nothing can be sent now, and a payment the queue could not send is shown with its reason.

**Driven on the emulator against the local Compose stack**, with the emulator's radio switched off:

| | |
|---|---|
| Network off | the screen said payments could still be taken |
| 50.00 and 60.00 TRY taken, no signal | both queued; the platform held 0 payments |
| `shared_prefs/queued-cards.xml` | an IV, ciphertext and an expiry per payment: no card, no 12-digit run |
| App killed while both waited, network back on, app reopened | "Sent 2 payments that were waiting." |
| The platform | 2 payments, amounts in the order taken, **each captured exactly once** |
| The vault afterwards | empty: no card kept once the authorizations were answered |

## Consequences

- **A card is on the phone while a payment is queued.** That is the decision, and it is the part to argue
  with. It is encrypted under a hardware-backed key, scoped to one payment, expires within a day, and is
  erased at the first answer — but a rooted phone with a queued payment on it has a card on it.
- **PCI scope.** Store-and-forward on the merchant's own device is a bigger question than this app answers
  today. A real deployment would keep the card out of the phone entirely by having the acquirer tokenize
  it at the point of sale; there is no such endpoint on this platform yet, and adding one is the honest
  next decision rather than something to pretend was done here.
- **An offline payment is a risk the merchant takes.** The card is not checked with anyone until there is
  signal, so it may decline hours later. The app shows that as a conflict; no platform change makes it not
  true, and it is why the queue tells the merchant rather than swallowing it.
- **The queue only runs while the app is alive.** Nothing sends payments in the background yet; opening
  the app or the network returning starts a pass. A `WorkManager` job would send them without the app
  being opened, and would need the card handed to a worker, which is a decision of its own.

## Alternatives

- **Refuse payments with no signal.** Honest, and useless: it is the case the merchant needs the app for.
- **Keep the card under the session's key.** One alias, two lifetimes; signing out would have to choose
  between keeping a card it should not and destroying a payment it should keep.
- **Keep the card until the payment finishes, rather than until it is authorized.** Simpler, and wrong: a
  capture never needs the card, so keeping it through capture is keeping it for nothing.
- **No expiry.** A card kept until the phone next has signal is a card kept indefinitely when the phone is
  lost with a queued payment on it.
- **Keep only a PAN hash or the last four.** Cannot authorize; it would make the queue a list of payments
  that can never be sent.
