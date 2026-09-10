# ADR 0034: A loop that learns from people has to be bounded, slow, and reversible

- Status: accepted
- Date: 2026-09-10
- Jira: MIZ-59

## Context

MIZ-58 gave the platform a way to stop a payment. This story gives somebody a way to lift the
stop, and then does something the rest of the platform has carefully avoided so far: it lets
what a person decided change what the platform will decide next time.

Everything else here is a pure function of what was written down. The scorer's rules are fixed,
the thresholds are set by a person, the ledger only ever adds. This is the first mechanism where
the platform's future behaviour depends on its own past output, and that is a different kind of
thing to get wrong: a bug in a rule produces a wrong answer, a bug in a feedback loop produces a
platform that has been taught something.

Two questions had to be answered deliberately.

## Decision

### Releasing does not authorize

An analyst's ruling says *the scorer was wrong about this one*. It does not say *charge this
customer*. Taking the money remains something the merchant does, through the same endpoint as
any other payment.

Partly this is forced: this service keeps four digits of the card and nothing more, so it could
not authorize if it wanted to — the merchant has to present the card again. But the forcing is
welcome, because collapsing the two would mean an analyst's click charging a customer, and that
is a larger thing than the button looks.

So a released payment stays `HELD_FOR_REVIEW` until the merchant authorizes it. "Still held" and
"still waiting for a person" therefore stop being the same question, and everything that reads
the queue asks the second one. A queue that asked the first would keep offering payments that
have already been ruled on, and the expiry sweep would quietly decline payments somebody had
released — overruling a person by doing nothing, which is the worst way to be overruled.

### A held payment is refused an authorization until somebody rules

The obvious implementation of MIZ-58's "a released payment is not scored again" is to skip
scoring whenever the payment is already `HELD_FOR_REVIEW`. That is a hole with a queue drawn
around it: the merchant whose payment was held can simply send the authorization again, and the
second attempt skips the scorer *because* the first one was held.

So authorizing a held payment is refused outright unless a person has released it. Being held
has to mean stopped, including — especially — to whoever it was applied to.

### Only a person rules

Who ruled comes from the access token, never from the request body: a name a caller types is a
name a caller can choose, and the only attribution worth keeping is the one the gateway proved.

More than that, a merchant's own API key may not rule at all. An API key is what a merchant puts
in a cron job, and a control a merchant can put in a cron job is not a control. This is the first
endpoint on the platform to care about the difference between a person and a key, which is why
`Principal` moved out of the gateway and into the shared identity module and why `Caller` now
carries it.

### The loop is bounded, slow, consecutive, and kept separate

The learned adjustment moves a merchant's thresholds, and:

- **It takes three consecutive rulings the same way** before anything moves, and another three
  before it moves again. Consecutive, not cumulative: a merchant whose analysts release three in
  a row is telling the platform it is too suspicious, and a merchant whose analysts disagree with
  each other is telling it nothing. A count that never reset would eventually move on noise.

  Every three rather than every one after the first three, which is what the obvious
  implementation does and what the live check caught it doing. A run of six is a stronger signal
  than a run of three and should be worth more — twice as much, not four times. Moving on each
  ruling past the third reaches the limit in six rulings, which is not a slow loop.
- **It moves five points at a time**, so the drift is slow enough for a person to notice it
  happening rather than discover it afterwards.
- **It stops at twenty points** in either direction, against thresholds that start at 40 and 70.
  Enough to matter, not enough to turn the scorer off. Somebody with an unlimited supply of held
  payments could otherwise teach this platform to stop looking, one release at a time, and the
  last thing they should have is a way to make that go faster. When a merchant reaches the limit
  and keeps ruling the same way, that is logged at warning: it means a person should look at
  their thresholds, which is a decision for a person and not for the loop.
- **The bound is also a check constraint.** A bound the loop enforces is a bound the loop can be
  wrong about.
- **What the loop inferred is stored separately from what a person set.** `learned_adjustment`
  is its own column, added at read time. So a merchant can always see how far the platform has
  drifted from their instruction, and can set it back without having to work out what it used to
  be. A loop that overwrote the instruction would be unreversible for the ordinary reason: not
  because anything stops you, but because nobody remembers what the number was.
- **A ruling is append-only**, by trigger, like the journal and like MIZ-53's operator
  decisions. A ruling is evidence of a decision somebody made, and evidence the next decision can
  overwrite is not evidence. It also keeps what the scorer said *at the time* beside it, because
  that is exactly what nobody can reconstruct later, and a ruling that did not keep it cannot be
  used to judge the scorer.

### The scorer is told, but a ruling does not depend on it

ADR 0033 decided that risk is a guard rather than an invariant and that the platform fails open
when it cannot be asked. The same judgement applies in the other direction: an analyst should not
be unable to release a customer's payment because the scorer is down. Telling risk is attempted
first and swallowed if it fails, logged at warning, because a ruling the scorer never hears about
is the feedback loop quietly not happening — survivable, and worth noticing.

## Consequences

- A merchant whose analysts consistently agree with each other gets a scorer tuned to them,
  within twenty points, over at least three rulings, visibly and reversibly.
- The platform now has a mechanism that an attacker could try to teach. It is bounded in the
  code, bounded again in the database, requires a person, requires a run of agreement, and leaves
  an append-only record of every ruling that moved it. That is four independent things to get
  past, which is the correct number for the only mechanism of its kind here.
- A released payment that the merchant never authorizes sits held forever. That is deliberate:
  the alternative is the platform authorizing on a person's behalf, and the expiry sweep exists
  for payments nobody ruled on rather than for payments nobody acted on. If merchants leave
  releases unused in practice, the fix is to tell them, not to charge their customers.
