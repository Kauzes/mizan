# ADR 0061: The phone asks, because nothing here can tell it

- Status: accepted
- Date: 2026-09-17
- Jira: MIZ-101

## Context

A merchant wants two things from the app that both sound like "the phone is told something": a list of
payments that stays current while they watch it, and to know when a payment is held for review — which is
the one payment state that needs a person and where nobody has been charged yet.

Neither can be delivered the way a payments app normally delivers them:

- **There is no push.** Firebase Cloud Messaging needs a Firebase project, a `google-services.json` and a
  server key. This repository has none of them, and inventing them would mean a notification path that
  exists in the code and has never delivered anything.
- **There is no stream either.** The platform has no websocket and no server-sent events. The gateway
  routes request-and-answer HTTP, the console reads the same way, and adding a streaming endpoint is a
  platform decision, not something an app story gets to take on the side.

The Jira story says this in as many words: use Firebase if the credentials are there, otherwise deliver it
another way and say plainly which one — and do not claim push that does not exist.

## Decision

**The app asks. A screen that is open asks every five seconds; a scheduled job asks every fifteen minutes
whether anything is held, and raises an ordinary local notification when something is. There is no push in
this app, and the README and this ADR say so.**

- **The live list is a poll.** `PaymentFeed` reads the merchant's payments every five seconds for as long
  as a screen is collecting it, and stops the moment nothing is. A phone in a pocket asks for nothing.
- **A failed read never empties the list.** What is on screen stays, with a line saying it is not up to
  date. A till that blanks when the signal dips is worse than one that is a few seconds stale.
- **The notification is local, raised by this app.** `HeldPaymentsWorker` runs on WorkManager's schedule —
  fifteen minutes is the shortest period Android honours, and it is a floor, not a promise. It asks for
  payments held for review and mentions any it has not mentioned before.
- **What is already on screen is not asked for twice.** While the merchant watches the list, held payments
  in it are announced from that list rather than by asking again, so something held while they are looking
  arrives in seconds rather than at the next scheduled run.
- **Mentioned once.** The ids mentioned are remembered. The scheduled check, which sees every held payment,
  is also what forgets one that has been ruled on — so a payment held again later is mentioned again. The
  screen's list is the most recent payments rather than all held ones, so it only ever adds to that memory;
  it is not in a position to decide something has stopped being held.
- **Refusing notifications is not an error.** The permission is asked for once. Without it the held payment
  is seen on the payments screen instead, and nothing else changes.

## Evidence

**Unit tests, 11 new.** `PaymentFeedTest` (4): what the platform lists is what the screen shows; a failed
read keeps the payments and says it is not up to date; a refusal shows the platform's own reason; a session
that ended is said once. `HeldWatcherTest` (7): a held payment is announced; the same one is not announced
again; one held since the last check is; one that was ruled on and is held again is announced again; a
check that could not reach the platform tells nobody and forgets nothing; a held payment in a list already
on screen is announced without asking the platform again; and that list never decides something has
stopped being held.

**On an Android 16 emulator, 28 (3 new).** `PaymentsScreenTest`: a held payment says it is waiting for a
person; a list that could not be refreshed keeps its payments and says so; nothing taken yet reads as empty
rather than broken.

**Driven on the emulator against the local Compose stack.** The merchant's review threshold was set to 4
for the run (a per-merchant threshold is a platform feature, MIZ-57), so that an exactly round amount —
five points on the scorer — is held deterministically:

| | |
|---|---|
| A 43.21 TRY payment created through the API while the phone sat on the payments screen | it appeared in the list within seconds, with nothing tapped |
| 1000.00 TRY taken on the phone | "Held for review: 1000.00 TRY. Nobody has been charged."; the platform: `HELD_FOR_REVIEW`, risk verdict `REVIEW` |
| The phone's notifications | title "Held for review", text "1000.00 TRY is waiting for a person. Nobody has been charged." |
| Watching the list again | still one such notification: it was not announced twice |

The first attempt at that drive failed for a reason worth recording: the check had been wired to the
payments screen's view model being created, and Navigation3 keeps a view model for a key across a pop and
a re-entry, so re-opening the screen never ran it again. Hanging a recurring duty off a screen's creation
was the mistake; it now hangs off the reads the screen is doing anyway.

## Consequences

- **A held payment is announced within minutes, not instantly**, unless the merchant happens to be watching
  the list. That is the cost of having no push, and it is the honest version of this feature.
- **Polling costs requests.** Five seconds while a screen is open is cheap for one merchant and would not
  be for a thousand phones; the gateway's per-merchant rate limit (ADR 0053) is what stops that becoming
  the platform's problem, and a real deployment wants a stream rather than a shorter interval.
- **The background check only runs when Android lets it.** Doze, battery saver and a manufacturer's own
  killing of background work all delay it. It is a best effort by construction.
- **When there is a Firebase project, this changes.** The watcher and the notification stay; what changes
  is what wakes them. That is one class, and it is why the notification is behind `HeldNotifier` rather
  than written where the check runs.

## Alternatives

- **Claim push and implement FCM against credentials nobody has.** Untestable here and dishonest.
- **A foreground service that polls constantly.** A permanent notification and a battery cost, to save
  fourteen minutes on a notification that is about a payment a person has to look at anyway.
- **Server-sent events from the gateway.** The right answer for the live list, and a platform decision:
  it needs a route, a connection budget and a story about reconnection. Worth its own ticket.
- **Poll only on opening the app.** Then the merchant learns about a held payment when they happen to look,
  which is exactly what the story is trying to fix.
