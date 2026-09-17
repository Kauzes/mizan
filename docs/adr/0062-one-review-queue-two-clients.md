# ADR 0062: One review queue, two clients, no phone-shaped copy of it

- Status: accepted
- Date: 2026-09-17
- Jira: MIZ-102

## Context

Payments held for review are ruled on in the console today. The phone should be able to rule on them too:
a held payment is a customer standing at a counter, and "wait while I find a laptop" is not an answer.

The temptation with a second client is to give it its own endpoint — something that returns what a phone
screen wants, shaped the way a phone screen wants it. That is how two clients end up with two queues that
disagree, and how a ruling made in one place becomes invisible in the other. The rules about ruling live on
the platform (a reason is required, a payment can be ruled on once, the scorer is told before anything is
written) and none of them can be re-implemented in an app without eventually differing.

## Decision

**The phone uses the console's endpoints, unchanged: `GET /reviews`, `POST /reviews/{id}/release` and
`POST /reviews/{id}/refuse`. No endpoint was added, and nothing about the queue is decided in the app.**

- **The same permission gate.** `REVIEW_RULE`, checked by the platform. An account without it is told "this
  account does not rule on held payments" — the console's own sentence — rather than shown an empty queue
  or an error.
- **A reason is required here too.** The platform refuses a ruling without one; the app does not offer the
  buttons until there is one, so nobody learns the rule by being refused. The reason is trimmed before it
  is sent, and it is the merchant's words that land in the payment's history.
- **A refusal is shown in the platform's words.** "This payment was already refused by …" is better than
  anything the app could compose, and it is the sentence that tells a merchant what actually happened.
- **The queue is polled, like the payments list** (ADR 0061), so a payment somebody else rules on leaves
  this screen by itself. Two people on one queue is the normal case, not the exception.
- **A payment ruled on here leaves the list at once**, without waiting for the next read, and the platform's
  answer is what decides — an optimistic removal that a failed ruling does not perform.
- **Approving says what was decided, not what the status became.** Releasing records the ruling and leaves
  the payment held until it is authorized again (`Payment.releasedForReview`), so reading the status back
  would tell a merchant who just approved a payment that it is still held for review.
- **One HTTP caller for everything under `/api/v1/merchants/{id}`** (`MerchantCalls`), so the token, the
  401, the 4xx-as-refusal and the 5xx-as-no-answer readings are written once rather than per client.

## Evidence

**Unit tests, 8 new.** `HttpReviewsApiTest` (5) over real HTTP: the queue is read from the same path the
console reads; approving posts the trimmed reason to `/release` and declining to `/refuse`; a payment
somebody else ruled on comes back as the platform's own sentence; an account that may not rule is a refusal
and not a lost session. `ReviewQueueTest` (3): the queue is what the platform says is waiting; an account
that may not rule is told so; a failed read keeps the queue on screen.

**On an Android 16 emulator, 36 (8 new).** `ReviewsScreenTest`: what a held payment shows and why it was
held; nothing can be ruled on without a reason; the reason typed is reported for that payment; approving
sends that payment and declining is a different button; nothing else can be ruled on while a ruling is in
flight; a payment somebody else ruled on is shown in the platform's words; an account that may not rule is
told so and offered nothing; an empty queue says everything has been ruled on.

**Driven on the emulator and in a browser at the same time**, against the local Compose stack, with three
payments held (the merchant's review threshold set to 4, as in ADR 0061):

| | |
|---|---|
| The phone's queue | all three, each with why risk held it |
| Approved on the phone, reason "known customer, approved on the phone" | the platform recorded `RELEASED`, and the payment's history carries that sentence |
| The console, signed in beside it (Playwright) | the payment the phone approved was **not** in its queue |
| Declined in the console | the platform recorded `DECLINED` / `REFUSED` |
| The phone, untouched | that payment left its queue on its own |
| A third payment ruled on through the API while the phone showed it, then approved on the phone | "This payment was already refused by …"; the platform kept **one** ruling and one decline |

## Consequences

- **The phone can do something the console can do, and no more.** A permission change on the platform
  changes both clients, which is the point.
- **Two clients polling one queue** is two sets of requests. At five seconds while a screen is open this is
  cheap; it is the same trade ADR 0061 already took, and the same answer (a stream) would fix both.
- **A ruling still needs typing on a phone.** Nothing here shortens that, and a queue that is quick to
  clear is a queue somebody clears without reading. The reason field is not a formality.
- **The cross-client check is not in CI.** It needs an emulator and a browser against one stack; the
  console's own e2e suite and the app's tests each cover their half, and the drive script that covers the
  seam lives with the other live checks rather than pretending to be a unit test.

## Alternatives

- **A phone-shaped review endpoint.** Two queues that will differ, and a second place for rules about who
  may rule.
- **Rule without a reason on the phone, because typing is awkward.** The platform refuses it, and rightly:
  a ruling nobody can account for is not a ruling.
- **Keep the ruled payment on screen until the next read.** Simpler, and it invites a second tap on a
  payment that has already been ruled on.
- **Let the phone re-authorize a released payment automatically.** Tempting, and a different decision: it
  would charge a customer as a side effect of an approval.
