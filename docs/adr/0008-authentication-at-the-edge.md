# ADR 0008: Authentication happens at the edge, and identity travels on headers

- Status: accepted
- Date: 2026-08-30
- Jira: MIZ-30

## Context

Six services sit behind the gateway. Each one could verify a token itself, which means each
one could get it wrong, and a service added later could forget entirely. The platform needs
one place where a caller stops being anonymous.

MIZ-29 made a token that anyone holding the public key can verify without asking identity
anything. This decides who does the verifying, what a service receives afterwards, and what
is still reachable without a token at all.

## Decision

**The gateway authenticates; services trust what it passes on.** A verified request reaches a
service carrying `X-Mizan-User`, `X-Mizan-Merchant` and `X-Mizan-Roles`. A service never sees
the token, and never has to decide whether a caller is real.

**Those headers are stripped from every inbound request before they are set** — on public
routes as well, because a public route still forwards downstream. Setting them without
stripping them would be worse than having no authentication at all: anyone could be anyone by
typing a header, and it would look authenticated. Stripping is by an explicit list rather than
an `X-Mizan-` prefix rule, because MIZ-32 introduces `X-Mizan-Key` and `X-Mizan-Signature`,
which a caller is *meant* to send.

**The merchant comes from the token, never from the request.** That is the single line that
makes tenant isolation possible in every story after this one, and it is the reason a caller
naming their own merchant in a header changes nothing.

**What is public is a list, not a pattern.** Signing in, refreshing and registering, health,
and the API specs. Anything not on it needs a token. A deny list or a pattern fails open — a
new endpoint under a prefix somebody thought was public becomes public without a decision —
whereas forgetting to add a route to this list produces a 401, which is the failure somebody
reports rather than the one nobody notices.

**`/internal/**` is no longer open.** Two things stay reachable: a service's `/v3/api-docs`,
because a published contract is documentation, and `/actuator/health`, because a probe holds
no credentials and whether the platform is up is not a secret. Everything else behind those
routes now needs a token like anything else.

**The gateway verifies with a fetched public key, not a configured secret.** It reads
identity's JWKS over HTTP and caches it, refetching when a key id it has never seen turns up,
which is what a rotation looks like from here. A floor on refetching keeps unknown key ids
from turning into traffic at identity. Nothing the gateway holds could mint a token.

**A key set that cannot be fetched is a 503, not a 401.** Telling a caller their credentials
are bad when the problem is ours sends them off to fix something that is not broken.

**No Spring Security.** The filter verifies a signature, an issuer and an expiry, using the
same JOSE library Spring Security uses underneath. Bringing the framework in would add a
second error model to reconcile with the platform's problem details, a second place where
route rules live, and a filter chain the gateway does not otherwise need.

## Consequences

The gateway is now a single point of failure for authentication, which it already was for
routing. A token it has verified stays valid for its lifetime regardless, so identity being
down does not lock anyone out mid-session.

A service that is reached directly, bypassing the gateway, gets whatever headers the caller
sent. Nothing in the platform does that today — services are only published locally for
debugging — but the day one service calls another directly, that call has to carry an
identity the callee can check, and this decision does not cover it. MIZ-31 is where a service
starts acting on these headers, and that is the point to revisit it.

Rendering problem details at the gateway is separate code from the servlet exception handler,
because a request is refused before any handler runs. Both build the body through the same
helper, and a test holds the gateway's own mapper to the flat shape the rest of the platform
returns, since a nested body is the kind of drift nothing else would catch.

Swagger UI at the gateway still works without signing in, which is a deliberate consequence
of leaving the spec routes public. Anyone who can reach the gateway can read what the platform
offers. They cannot call any of it.

## Alternatives considered

**Spring Security's resource server.** The conventional answer, well tested, and it would own
the 401 body and the route rules. Reconciling its error shape with RFC 9457 problem details
that carry a correlation id and a stable code is the work it saves, given back.

**Verify in each service instead.** Removes the gateway as a single point of authentication
and lets a service be reached directly. It also means six implementations of the same check
and a seventh that forgets. A shared library helps and does not stop somebody from not using
it.

**Sign the propagated headers, or forward the token itself.** Would let a service verify the
gateway's claim rather than trusting the hop. Worth doing the day a service is reachable
without going through the gateway; today it would be ceremony protecting a network path that
does not exist.

**Lock `/internal/**` completely.** Simpler to explain, and it would take the documentation
browser down with it — the thing that makes the platform legible to anyone reading it for the
first time. Splitting the specs and health out is a slightly longer public list in exchange
for keeping that.
