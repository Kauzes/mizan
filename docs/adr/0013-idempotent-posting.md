# ADR 0013: A posting carries a reference, and a retry is answered with the first result

- Status: accepted
- Date: 2026-09-01
- Jira: MIZ-36

## Context

A caller that does not hear back does not know whether the money moved. It will retry, because
the alternative is leaving a payment in limbo. In a ledger, a retry that posts twice is money
invented out of a dropped response, and the second posting is indistinguishable from a real
one afterwards.

## Decision

**The reference is required, not offered.** Every entry carries what the caller calls this
movement. An optional idempotency key is a key that gets left out of the one call that needed
it, and in a ledger every movement should be traceable to what caused it anyway.

**A replay is answered with the first call's entry, identically, including the status.**
Returning 200 where the first call returned 201, or a conflict, means a client has to handle a
second shape of success on the path it reaches only when something already went wrong. The
retry is answered as though it were the original because from the caller's side it is.

**Uniqueness is discovered by inserting.** A check before the insert answers for the moment
before the insert, and two retries racing would both be told the reference was free. The
insert either succeeds or violates a unique index, and the violation is what triggers the
replay.

**A digest of the request is stored alongside it.** The same reference sent with different
postings is somebody's mistake — a key reused, a loop variable not advanced — and answering it
with an unrelated entry would hide that behind a success. The stored fingerprint is what tells
a genuine retry from a collision. The request itself is not kept: the only question ever asked
is whether this is the same request.

**Postings are ordered before hashing.** The same two postings in the other order are the same
movement of money, and a client building its list from a map should not be told its retry is a
different request.

**References are scoped to the merchant.** Two merchants numbering their invoices from one is
not a collision.

**Transactions are managed explicitly here, not by annotation.** A constraint violation leaves
the transaction that hit it unusable, so the replay has to read in a new one — and a
`@Transactional` method called from inside the same bean is called directly rather than
through the proxy, so it would run with no transaction at all. That is not a hypothetical: the
first version of this did exactly that and failed on a lazily loaded account.

**Instants are truncated to what the database stores.** Postgres keeps microseconds. An
instant carrying nanoseconds is reported one way by the call that wrote it and another by
every call that reads it back, including a replay of the same request — which is precisely the
comparison this story promises will come out identical.

## Consequences

The reference is now part of the ledger's public contract, and a caller that generates a fresh
one per attempt gets no protection at all. That is worth saying plainly in the spec, and it is
said there: the reference identifies the movement, not the attempt.

The fingerprint is a hash of the request as this version canonicalises it. Changing what goes
into it — adding a field, changing the ordering rule — invalidates every stored fingerprint,
which would turn genuine retries of in-flight requests into conflicts. Any such change needs a
migration that says what it does to the old values.

An entry cannot be posted without a reference even by an operator writing SQL by hand, because
the column is not null. The migration backfills entries written before this one with their own
id, and does so by standing the append-only trigger down for the length of one statement —
which is the intended way past that rule, in a reviewed change, and the only one.

Nothing expires a reference. The books keep every entry forever, so a reference stays claimed
forever, which is correct for a ledger and would not be for a general purpose idempotency
cache.

## Alternatives considered

**An `Idempotency-Key` header, as Stripe does.** The platform-wide convention this eventually
wants, and it belongs in the shared module once a second service needs it, which MIZ-4 will
decide. Here the reference is not only an idempotency device: it is what ties an entry to the
payment that caused it, so it belongs in the body as part of the movement rather than beside
it as transport.

**Returning 409 on a replay.** Honest about what happened and hostile to the client, which has
to treat one of its two success paths as an error. The conflict is kept for the case that
really is one: the same reference for a different entry.

**Storing the whole request rather than a digest.** Would allow telling a caller exactly what
differed. It also means keeping a second copy of the books in a column nobody reads, and the
answer "this reference was used for a different entry" is enough to find the bug.

**Comparing the stored entry to the request instead of hashing.** No fingerprint column, and a
comparison that has to be kept in step with every field the request grows. The hash is one
column and one function, and both live next to each other.
