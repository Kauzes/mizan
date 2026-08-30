# ADR 0009: Roles carry permissions, and the tenant boundary is checked before the handler

- Status: accepted
- Date: 2026-08-30
- Jira: MIZ-31

## Context

MIZ-30 established who a caller is. This decides what they may do, and it has to answer a
question that gets harder with every epic: how does a service avoid reading another merchant's
rows? Not once, but in every endpoint anybody writes from here on.

## Decision

**A role is a set of permissions, written down.** `Role` maps to `Permission`, and an endpoint
declares the permission it needs. What a role means is a table to read rather than a pattern
to infer from scattered checks.

**Only permissions the platform can exercise exist.** No speculative `PAYMENT_REFUND` before
there are payments. An epic that adds endpoints adds the permissions they need and grants
them, which is why the role table is the one place to look.

**`OWNER` is defined as the whole set.** An epic adding a permission grants it to the owner
without a decision, and to nobody else without one. That is the correct default for the person
who owns the account, and it makes every other grant deliberate.

**Authorization is declared, not written.** `@RequiresPermission` on the handler, enforced by
an interceptor. Two consequences worth the trade: what an endpoint requires can be read from
its signature, and a missing declaration is something the platform can notice.

**A service refuses to start if an endpoint under `/api/` declares nothing.** Forgetting is
the ordinary way an API grows a hole, and it is invisible — the endpoint works, which is what
it also looks like when it is correct. Failing at startup turns that into the first run
failing rather than an audit finding later. The interceptor refuses an undeclared endpoint
too, so the runtime does not depend on the check having run.

**The tenant boundary is checked before the handler.** Where the path names a merchant, the
caller must be acting for that merchant, checked in the interceptor before anything is looked
up. An endpoint cannot opt out: one that wanted to would be one that reads across the
boundary.

**Refusals say nothing about existence.** A caller asking about another merchant is refused
identically whether that merchant exists, does not exist, or is a malformed id. Within a
merchant, lookups are scoped by merchant id in the query, so another merchant's user is
genuinely not found rather than found and then hidden.

**A merchant always has an owner.** The last owner cannot be removed or demoted. An account
with nobody able to administer it is recoverable only by hand in the database, and refusing
the last step is cheaper than undoing it.

**The spec documents required roles from the annotation that enforces them.** An
`OperationCustomizer` reads `@RequiresPermission` and writes the permission, the roles holding
it, and the 401 and 403 responses into each operation. Documenting the role by hand would be
documenting it twice, and the copy nobody enforces is the one that goes stale.

## Consequences

`Role` moved from identity-service into the shared module. Roles arrive at every service on a
header, so every service reads the same set — leaving the definition in identity would have
meant either a duplicate or a dependency on the service that issues tokens.

The interceptor and the startup check both key on `/api/`. Anything mapped elsewhere —
actuator, the generated spec, the published signing keys — is outside this scheme, because
those are not controllers anybody annotates. A future endpoint deliberately served outside
`/api/` would be outside the guard too, which is a real edge and the reason the prefix is
named in one place.

`ADMIN` and `ANALYST` are thin today: `ADMIN` reads people, `ANALYST` reads only the merchant.
That is honest rather than aspirational. The permissions that make those roles worth holding
arrive with the payment and review endpoints, and the role table is where they get granted.

A user of one merchant cannot act for another at all. An accountant working for several
merchants needs several accounts, which is the same limitation email uniqueness already
implies and has the same answer if it ever matters: an invitation flow, not a hole in this
check.

Tenant isolation is now covered by tests that exist to be copied. They use two merchants that
really exist, both with real data, because a test that proves isolation with an id that was
never created proves much less: a missing row refuses itself, while the failure worth catching
is the one where the row is there and the query forgot whose it was.

## Alternatives considered

**Spring Security's method security**, `@PreAuthorize("hasRole('OWNER')")`. Expressions in
strings, checked at runtime, with the tenant check written into each one by hand. The failure
mode is a typo that silently allows, and it would bring the framework this platform decided
against at the gateway in ADR 0008.

**Checking the merchant inside each service method.** Where most systems put it, and where it
is eventually forgotten once. Doing it in the interceptor makes it structural: the check runs
because the path has a merchant in it, not because somebody remembered.

**A permission per endpoint rather than per action.** Finer control, and a list nobody can
hold in their head. Four permissions that map to things people say out loud — read the
merchant, see the people, manage the people, change what they may do — is a set that can be
reasoned about.

**Roles as free text, so a merchant can define their own.** Flexible, and it makes every
authorization question a data question that cannot be checked at compile time or listed in the
spec. A closed set can be enforced by the database, which it now is.
