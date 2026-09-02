# ADR 0017: Every write says what a repeat of it does

- Status: accepted
- Date: 2026-09-02
- Jira: MIZ-41

## Context

MIZ-36 gave the ledger idempotency on the reference an entry carries, and said the shared
version belonged in the common module once a second service needed one. Payments need one:
a caller retrying an authorization is retrying an attempt to move somebody's money, and the
second attempt must not charge them again.

The ledger's mechanism does not generalise. Its reference is not only an idempotency device —
it is what ties an entry to what caused it — and not every write has such a natural handle.

## Decision

**Every write under `/api/` declares what a repeat of it does**, with `@Idempotent` or
`@NotIdempotent(because = ...)`, and a service refuses to start if one declares neither. The
same argument as the authorization declarations in ADR 0009: an endpoint that quietly does its
work twice looks exactly like one that correctly does it once, and nothing in the code says
which. Making the decision compulsory turns that into a failure on the first run.

**The opt-out carries a reason, in prose, for people.** There are three honest ones and the
codebase uses all three: already safe to repeat (a delete, a replace), carries its own
idempotency (posting to the journal), or has no merchant to scope a key to (registering,
signing in).

**A key is scoped to the merchant and the mapped pattern**, not the path that matched it. One
key means one thing per operation, so a caller can use the same key for the whole of one
business action without its two calls being confused.

**The claim is written and committed before the handler runs.** That is the entire concurrency
story: a unique constraint decides which request proceeds, and everybody else reads what the
winner is doing. A request that finds a claim still in flight is told so with `CONTENDED`
rather than made to wait or allowed to start a second attempt.

**What is stored is the response, so a repeat is answered with the same status and body.** A
caller retrying after a lost response cannot tell its retry from the original — otherwise it
would have to handle a second shape of success on the path it only reaches when something has
already gone wrong.

**A failure gives the key back.** A caller retrying after a 500 wants another attempt rather
than the 500 again, and one retrying after a 400 will be told the same thing by the handler.
Only a 2xx is worth replaying.

**The store is plain SQL against a table each service creates in its own migration.** An entity
in a shared module would have to be scanned into six persistence units, which is configuration
that has to be right in six places. The shape is identical everywhere because the code that
writes it is shared.

**The spec documents the header from the annotation**, as the required permission already is.

## Consequences

Every existing write endpoint had to declare itself, and several tests had to start sending a
key. That churn is the point: it is the moment each of those endpoints got a decision rather
than an absence.

The mechanism was silently inactive when first wired up. `@ConditionalOnBean` in an
auto-configuration only sees beans from auto-configurations that ran first, and this one was
not ordered after the JDBC ones, so the store was never created and every write went through
unguarded — while the whole suite stayed green, because nothing yet asserted the new behaviour.
The tests written for this story are what caught it, and the ordering is now declared. It is
worth remembering that a conditional bean that silently does not exist looks exactly like one
that does.

Nine of ten concurrent callers in the live check were replayed the winner's answer and one was
told the work was still in flight. That ratio is timing, not design: a caller that arrives
while the first attempt is running gets `CONTENDED` and is invited to send the same request
again, which is safe precisely because the key is the same.

Records accumulate and nothing prunes them. A key claimed today is claimed forever, which is
correct while the volume is small and is the first thing to revisit for a busy endpoint: the
`created_at` index is there for the sweep that will eventually want it.

The body is buffered for every write under `/api/`, up to a configured maximum. That is a real
cost on large requests and the reason the limit exists.

## Alternatives considered

**Optional keys, honoured when sent.** What Stripe does, and it means the one caller who most
needed idempotency is the one who forgot to send a key. Requiring it costs a client one header
and removes the failure mode entirely.

**A natural key per endpoint, as the ledger has.** Better where one exists, because it ties the
request to the thing that caused it rather than to the attempt. It does not generalise, and
inventing one per endpoint would be inventing a different mechanism each time.

**Waiting for the in-flight attempt and then replaying its answer.** Friendlier: nobody sees a
409. It also means holding a request open for as long as another one takes, which turns a slow
handler into a queue of held connections. Telling the caller to send it again is honest and
cheap, and safe because the key makes the resend a replay.

**Keeping the records in Redis with an expiry.** Fits their lifetime and prunes itself. It also
puts the record of whether money has already moved in a store the platform does not treat as
durable, and a restart that empties it makes every retry a second charge.
