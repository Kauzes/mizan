# ADR 0021: A capture crosses two sets of books, so it is not a merchant's to write

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-45

## Context

Capturing is where a payment stops being a promise and becomes a movement. ADR 0019 decided
that an authorization posts nothing, which leaves capture as the first thing in this platform
that both takes money and writes it down — in two services, with two databases, and no
distributed transaction between them.

The entry a capture writes touches the platform's clearing account and the merchant's
settlement account. One of those belongs to nobody in particular, which turns out to decide
most of the design.

## Decision

**The entry is posted through a route that is not a merchant route.** ADR 0011 scoped every
account lookup to the merchant, so a merchant-facing post cannot name the platform's clearing
account — and must not be able to, because every merchant's money passes through it and an
endpoint that let one merchant name it would let them credit themselves out of it. So capture
posts to `POST /internal/entries`, which widens what may be named to *the merchant's accounts
and the platform's, and nothing else*. Another merchant's account is refused there in the same
words as an account that does not exist, because from that call it does not.

**Three things have to be wrong before a merchant reaches it.** It is outside `/api/`, so the
declaration guard does not apply and neither does merchant scoping; the edge does not route to
it; and it requires a service credential no merchant holds. Any one of those would probably do.
Defence in depth is cheap here and the thing being defended is the account every merchant's
money sits in.

**The credential is a shared secret**, which is the weakest of the credible options and the
honest one for services that already share a deployment and a trust boundary. It is not a
caller identity: it says "a Mizan service", not which one, and it grants nothing by itself —
the merchant an internal call acts for is still named in the request and still checked. A
service refuses to start without one, rather than discovering at the first capture that its
internal endpoints have been open since deployment.

**Accounts are named by code, not by id.** The caller is another service, and the ids belong to
a migration in the ledger's database. `platform.` is the platform's prefix; anything else is
the merchant's.

**An account nobody opened is refused, not created.** ADR 0011 said a chart of accounts is
decided rather than arrived at, and that does not stop being true because a capture is
convenient. The refusal names the account, because "unprocessable" is not something an operator
can act on and "no account settlement.try in this merchant's books" is.

**The order is: acquirer, then ledger, then state.** Each failure was chosen by what it leaves
behind:

- Acquirer first, because until it has taken the money an entry would record something that did
  not happen — and ADR 0012 refuses to unwind entries, so that entry would have to be
  compensated forever.
- Ledger before state, so a payment never says captured while the books hold nothing. The
  database enforces the same thing: a `CAPTURED` row without an entry id fails a check
  constraint.
- The remaining window is the other direction — money taken, entry written or not, payment
  still `AUTHORIZED`. That one is visible and a retry away from finished.

**Every step is repeatable, which is what makes that ordering usable rather than merely tidy.**
The acquirer now answers a repeated capture with the capture it already made instead of
refusing it; the entry carries the payment's own id as its reference, so the ledger answers a
repeat with the entry it already wrote. Sending a capture again cannot take the money twice or
record it twice. Repeating is allowed; *reversing* is not — capturing something voided is still
refused, because that is a contradiction rather than a retry.

**A void posts nothing.** No money moved. An entry recording a movement that did not happen
would be worse than no entry at all. Releasing money that *has* been taken is a refund, which
is an entry rather than the absence of one, and is Epic 7.

## Consequences

**A merchant who has not opened a settlement account gets a refusal after the acquirer has
taken the money.** This is the honest consequence of taking money before recording it, and the
alternative — recording it first — is worse. It is recoverable: open the account, send the
capture again, and it finishes, which is covered by a test that does exactly that. What it
really says is that opening a merchant's settlement account belongs in onboarding, and
onboarding does not exist yet.

**The gateway's internal routes were a blanket `/internal/<service>/**`.** That forwarded every
internal endpoint any service would ever add, to anyone holding a token. Nothing exploited it
because no internal endpoint did anything interesting — until this story wrote one that moves
money, which a blanket route would have published the day it was written. They now forward a
published contract and a health probe and nothing else, and a test says so, so the next
internal endpoint is unreachable from the edge until somebody decides otherwise.

**Migrations are namespaced by service** — `db/migration/<service>` rather than the
`db/migration` all five shared. One classpath is enough to make the old layout ambiguous: a
test that runs two services in one JVM finds two `V1`s and Flyway refuses. The files and their
checksums are unchanged, so an existing schema history still matches.

**Payment-service's tests now start a real ledger as well as a real acquirer**, each with its
own database and its own configuration file. The alternative was a stub, which would have
tested that this service can talk to a stub — and the wire between two services developed
together is exactly where a silent break lives. The cost is a second test-scope dependency
between service modules and about a second of startup.

**A capture is now the one operation that can fail for a reason outside both this platform's
services**: the acquirer, the ledger, or the network between them. Its error messages pass the
ledger's own sentence through rather than replacing it with "internal error", because the one
useful sentence is usually the ledger's.

## Alternatives considered

**Letting the merchant-facing post name platform accounts.** One endpoint instead of two, and
it is the whole hole: the endpoint a merchant already has would move money out of the account
every merchant's money sits in.

**Having the ledger interpret a capture** — an endpoint that takes a payment and decides which
accounts it touches. Keeps the chart of accounts entirely the ledger's, and makes the ledger
know what a payment is. Posting is the ledger's job; knowing what a capture means is the
payment service's. The internal route stays a posting route.

**Opening the merchant's settlement account on first capture.** Removes the refusal, and makes
the chart of accounts something that grows because a request arrived, which ADR 0011 refused
for reasons that have not changed.

**Posting between two platform accounts instead**, with the merchant recorded only on the
entry. No merchant needs to have been set up, and per-merchant balances stop being account
balances and become a query over entries. The merchant's books are worth having.

**A two-phase commit across the two services.** Correct in theory, and it needs both services
to hold locks across a network call, which is how a slow ledger becomes a stalled acquirer.
Repeatable steps in a chosen order get the same guarantee with failures that are recoverable
rather than stuck.

**Signing the internal request the way merchants sign theirs (MIZ-32).** Stronger, and it wants
identity to hold a key for each service and a way to rotate them, which is a story rather than
a line of configuration. The shared secret is the honest small version, and it is written down
here as such so nobody mistakes it for the strong one.
