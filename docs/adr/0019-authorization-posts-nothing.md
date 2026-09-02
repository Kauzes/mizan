# ADR 0019: An authorization is a promise, and promises are not posted

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-43

## Context

Authorizing is the first time a payment leaves the platform. The acquirer either reserves the
money or refuses, and the platform has to record what it was told without recording more than
it was told.

## Decision

**Nothing is posted to the ledger.** An authorization is a promise that the money is there,
not a movement of it, and the books record movements. Posting a reservation would mean entries
that later have to be *unwound* rather than compensated, which is precisely the shape ADR 0012
refused for the journal.

If a merchant-facing "available balance" ever needs to account for authorizations in flight,
that is a read model over payment state, not an entry. The live check for this story confirms
the books are untouched by an approval and by a decline.

**The acquirer is asked with the payment's own id.** One authorization per payment, and asking
again returns the decision already made rather than reserving the money a second time. That
composes with the acquirer's own idempotency from MIZ-42 and is what makes a retry after a lost
answer safe.

**A decline keeps the reason the acquirer gave**, rather than a reason of this platform's
invention, because the merchant will be asked by a customer and "declined" is not an answer.

**The state is checked before the acquirer is troubled.** A payment that cannot be authorized
is refused in terms of where it already is, rather than after somebody else's system has done
work on our behalf.

**A timeout is not a decline.** The client raises `UPSTREAM_TIMEOUT`, the payment stays where
it was, and nothing pretends to know. Deciding a payment failed because we stopped listening is
how a customer is charged for something the merchant believes never happened.

**Only the last four digits of the card are kept**, and a test asserts the number is nowhere in
the stored row. The card is a parameter of the authorize call, not a property of the payment:
an intent is created by the merchant, and the card arrives later and belongs to whoever
presented it.

## Consequences

**A timed-out authorization leaves a payment that says `CREATED` while the acquirer holds the
money.** That is the honest state given what this story knows, and it is deliberately left
open: MIZ-44 adds the state that says the outcome is unknown and the resolution that asks. The
live check for this story ends by showing exactly that — the payment saying `CREATED`, the
acquirer saying `APPROVED` and `HELD` — because a gap that is demonstrated is a gap somebody
will close.

The payment service is now the only service that calls out to somebody else's system, and the
first with a configured timeout on an outbound call. That timeout is the number that decides
how often the unknown state happens.

Its tests start the real acquirer in the same JVM rather than stubbing it. A stub would encode
this service's assumptions about the wire and keep passing if the acquirer changed shape, which
is the failure most worth catching between two services that are developed together. The cost
is a test-scope dependency between two service modules and a configuration file to keep their
two `application.yml` files from colliding on one classpath — both written down where they
happen.

## Alternatives considered

**Reserving funds in the ledger at authorization.** Makes an available balance trivial to
compute, and puts entries in the journal that represent money that has not moved. Unwinding
them on a void means either deleting entries, which the journal refuses, or writing
compensating entries for something that never happened.

**Taking the card at intent creation.** Fewer round trips, and it means a card number sits in a
record from the moment a merchant decides to charge somebody, rather than arriving with the
attempt to charge.

**Treating a timeout as a decline and letting the customer retry.** Simple, and it charges
people for payments their merchant believes were refused. The whole of MIZ-44 exists to avoid
this.

**Stubbing the acquirer in tests.** Cheaper and better isolated, and it would have tested that
this service can talk to a stub. The wire format between these two is exactly where a silent
break would live.
