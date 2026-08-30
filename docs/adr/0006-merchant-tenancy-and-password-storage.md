# ADR 0006: The merchant is the tenant boundary, and how a password is stored

- Status: accepted
- Date: 2026-08-27
- Jira: MIZ-28

## Context

Nothing in the platform can be attributed to anybody until a merchant and a user exist. Every
table that comes after this one — accounts, payments, risk decisions, webhooks — hangs off a
merchant, so the shape chosen here is the shape the rest of the schema inherits. The same
story is the first place the platform holds something it must never give back.

## Decision

**A merchant is the tenant boundary.** Every later table carries a merchant id, and a query
that forgets to filter on it is the bug this arrangement exists to make visible. Users belong
to exactly one merchant; a person acting for two merchants gets two accounts.

**An email address identifies a user globally, not within a merchant.** It is unique across
the platform, enforced by a unique constraint plus a check that the stored value is already
lowercased, so a second account cannot be opened by changing the case of an address that is
taken. This lets a sign-in ask for an email and a password and nothing else.

**Registration is one transaction.** The merchant and its first user are created together, and
that user gets `OWNER`. A merchant nobody can sign in to could only be repaired by hand.

**Duplicates are found by inserting, not by asking first.** The insert is flushed inside a try
block and the constraint violation becomes a `CONFLICT` problem detail. Asking whether an
address is free answers for the moment before the insert, not for the insert itself, and two
registrations racing would both be told it was free.

**Passwords are stored as bcrypt hashes.** Salted per row and deliberately slow, with the cost
factor in configuration and recorded inside each hash, so raising it later leaves existing
hashes verifiable. Tests run at the production cost. The dependency is
`spring-security-crypto` alone — the hashing, without the filter chain, which arrives at the
gateway in MIZ-30.

**The plaintext has nowhere to go.** No response record has a field for a password or a hash,
the entity exposes no accessor for the hash, and the request record overrides `toString` to
redact. Each of those is covered by a test, including one that captures the logs at debug
level during a successful and a failed registration.

**Roles land now and are enforced later.** The table and the closed set (`OWNER`, `ADMIN`,
`ANALYST`, `VIEWER`) arrive with the rest of the schema, because schema is cheapest to settle
once. What each may do is MIZ-31.

## Consequences

A merchant id has to be threaded through every later feature, and tenant isolation becomes
something to prove rather than assume. MIZ-31 tests it against a real second merchant, and
those tests should outlive that story.

Globally unique emails mean one person cannot hold accounts at two merchants under one
address. That is a real limitation for a shared bookkeeper or an agency, and the fix if it
ever matters is an invitation flow, not a change to this constraint.

Insert-and-catch means the conflict path depends on a constraint the migration owns. Removing
that constraint would turn a clean 409 into a duplicate row, which is why the test asserts the
status and the code rather than the message.

bcrypt caps the password at 72 bytes, which the 200 character limit does not reflect. It has
not bitten because the limit is a validation rule rather than a promise about entropy, but a
move to argon2id is the obvious upgrade and the recorded cost factor makes migrating hashes
lazily, at next sign-in, straightforward.

Registration is open to anyone who can reach the endpoint. Until MIZ-30 puts authentication at
the gateway, that is every caller; sign-up throttling is MIZ-13's.

## Alternatives considered

**Email unique per merchant.** Lets one address act for several merchants, at the cost of
every sign-in needing to know which merchant it is for — a selector on the form, or a merchant
slug in the URL. Not worth it before anything has asked for it.

**Argon2id instead of bcrypt.** Better resistance to hardware attack, and available in the same
library. It is also memory hungry in a way that wants tuning per environment, and the platform
has no threat model written yet to tune against. bcrypt at a configurable cost is the boring
choice, and the stored format makes moving later cheap.

**Full Spring Security in identity-service.** Brings a filter chain this service does not use,
since nothing here is authenticated yet and the enforcement point will be the gateway. The
crypto module alone is the part actually needed.
