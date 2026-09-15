# ADR 0055: A capture writes down that it began, and a sweep finishes what nobody finished

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-90

## Context

MIZ-90 asks that the ledger can be killed in the middle of the payment flow, under load, without a
payment being lost. Reading the flow before writing the chaos test showed it would not pass, and why.

A capture has two steps with two other systems: the acquirer takes the money, then the ledger records
it. The order is deliberate (the books never record a movement that did not happen), and both steps
are repeatable (the acquirer answers a repeated capture with the one it made; the ledger entry is
keyed on the payment's id). So a capture interrupted between them was always *repairable*: sending it
again finishes it.

But nothing repaired it except the merchant sending it again. The capture ran in one transaction, so
when the ledger failed everything rolled back and the payment said `AUTHORIZED` exactly as before. No
field recorded that a capture had begun. There was no sweep for captures, as there is for refunds
(`RefundResolver`) and unknown authorizations (`AuthorizationResolver`), and the operator view of stuck
payments could not show what nothing marked.

So a ledger outage left money taken at the acquirer and absent from the books, visible to nobody, for
every merchant who did not retry. The books still balanced, because nothing was written. That is what
makes it dangerous: every integrity check this platform has would have passed.

## Decision

**A capture writes down that it began, in its own committed transaction, before the acquirer is asked.
A sweep finds captures begun and not finished, asks the acquirer what it did, and does what the answer
means. It never guesses.**

- **A column, `capture_started_at`, not a new status.** The payment is still exactly as authorized as it
  was; what is new is that something began. The same distinction as `needs_attention_since`. A new
  status would have rippled through the console's filters, the published API, search, the summary and
  two constraints, for a state that lasts milliseconds when things work.
- **Each step of a capture commits on its own**, in a new transaction whatever the caller is in:
  1. refuse, or set `capture_started_at`, and commit;
  2. ask the acquirer to capture;
  3. record in the ledger, mark `CAPTURED`, clear the mark, write the event, and commit.
- **What each failure leaves:**

  | Failure | Payment afterwards |
  |---|---|
  | Acquirer refuses (4xx) | mark cleared: nothing was taken |
  | Acquirer times out or is unavailable | mark stays: nobody knows |
  | Ledger fails | mark stays: the money is taken and not recorded |
  | payment-service dies anywhere after step 1 | mark stays |

- **`CaptureResolver` sweeps every 15 seconds** for authorized payments whose mark is older than 10
  seconds, and asks the acquirer where the authorization is now:

  | The acquirer says | The sweep |
  |---|---|
  | `CAPTURED` | records it in the ledger and marks the payment captured, as the request would have |
  | `HELD` | clears the mark: the capture never arrived, and can be sent again |
  | anything else, or no record | asks a person, with the acquirer's own words |
  | cannot be asked, or the ledger still fails | counts an attempt; after 5, asks a person |

- **Racing is safe.** The original request arriving late, the merchant retrying, and two pods' sweeps
  can all try to finish one capture. The ledger entry is keyed on the payment's id, a payment already
  captured is returned as it is, and the version on the payment lets only one writer mark it.
- **The acquirer's words are held to the simulator's.** The sweep first expected `AUTHORIZED` where the
  acquirer says `HELD`, so every capture that never arrived went to a person. A test that exercised
  that path found it. `AcquirerStatesTest` now checks every state the sweep acts on against the
  simulator's own list.

## Evidence

`CaptureResolverTest`, with the real acquirer and ledger wrapped so the test can break them at a chosen
moment, and never retrying a capture on the merchant's behalf:

- The ledger fails mid-capture: 503, payment authorized, no entry, **mark set**. Ledger back, one pass:
  **captured, one entry, mark gone**.
- The acquirer takes the money and the answer is lost: 504, mark set. One pass: **captured, one entry**.
- The capture never reaches the acquirer: 504, mark set. One pass: **still authorized, mark gone, no
  entry**, and a capture sent afterwards succeeds.
- The acquirer refuses: 422, **no mark**.
- Two sweeps and a merchant retry racing one capture: **one entry**.
- The ledger stays down: after 5 passes, **needs a person**, listed in the stuck payments with the reason
  "the acquirer has taken the money and the ledger has not recorded it", and the sweep leaves it alone.

**`scripts/chaos-ledger.sh`**, on the Compose stack: the steady load profile (ADR 0054) at 30 payments
a second through the gateway, with k6 never retrying a capture. ledger-service is killed 40 seconds in
and started again 40 seconds later.

| | |
|---|---|
| Requests | 13,857, 7.1% failed |
| Captures that failed while the ledger was down | **989**, all 504 |
| Captures the sweep finished afterwards | **989**, over three passes |
| Passes that could not finish yet, the ledger still down | 259 |
| Needed a person | **0** |
| Captures on the acquirer's own statement | **19,061, every one captured in the platform** |
| Captures the platform claimed that the acquirer did not make | **0** |
| Books afterwards | balanced: 19,287 entries |

"No payment lost" is checked against the acquirer, not the platform's own records. The statement is
asked for faithfully, and every capture it lists must be captured in the platform with its ledger
entry.

The same run against the code before this change was not performed. The 989 captures above are ones
the acquirer took and only the sweep recorded; with no sweep they would have stayed authorized, and
the acquirer comparison would have listed them. That is an inference from the logs, stated as one.

## Consequences

- **A capture is up to three transactions**, not one. Each holds a connection only for its own step,
  which is no worse than before: the single transaction held one across both network calls.
- **A ledger outage still slows every payment request, and that is not fixed here.** During the chaos
  run, creating a payment reached a p99 of 24 seconds, though creating one never touches the ledger.
  The last step of a capture holds a database connection while it waits up to five seconds on a ledger
  that is not answering, and enough of those take the pool, so everything else queues for a connection.
  It was true before this change too. The likely remedy is the one ADR 0052 gave the acquirer: a limit
  on how many calls may wait on the ledger at once. Left as a follow-up rather than widening this story.
- **A capture that the ledger refuses for a reason that will not go away**, such as a merchant with no
  settlement account, is now retried by the sweep and handed to a person after five passes. Before, it
  was silently abandoned. It still succeeds as soon as the merchant retries after fixing the cause.
- **The attempt counter is shared** with the authorization resolver, and reset when a capture begins.
  The two never apply to one payment at the same time: a payment is unknown or authorized, never both.
- **Voids are not covered.** A void interrupted after the acquirer released the money changes nothing in
  the books, so nothing is lost; the payment stays authorized and can be voided again. If that ever
  matters, the same mark works.

## Alternatives

- **Leave it to the merchant to retry.** It is how the API was documented, and it loses money whenever a
  merchant's integration does not, which is some of the time for every merchant.
- **Record in the ledger before capturing at the acquirer.** Then a failed capture leaves a movement in
  the books that never happened, which ADR 0012 refuses, and correcting it takes a reversal entry.
- **A `CAPTURE_PENDING` status.** Correct, and far more invasive for the same fact.
- **An outbox-style capture command, processed asynchronously.** Turns a synchronous API asynchronous, a
  larger change for merchants than the problem warrants.
