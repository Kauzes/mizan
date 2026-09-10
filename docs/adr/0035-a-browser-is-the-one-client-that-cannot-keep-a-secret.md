# ADR 0035: A browser is the one client that cannot be trusted to store a refresh token

- Status: accepted
- Date: 2026-09-10
- Jira: MIZ-60

## Context

Every client this platform has had so far runs on a server somebody controls: the smoke script,
the seed script, a merchant's own integration. Handing those a refresh token in a JSON body and
letting them keep it is fine, because "keep it somewhere safe" is a thing a server can do.

The console is the first client where that sentence has no meaning. A refresh token is a
credential good for thirty days. Every place a page can put one — `localStorage`,
`sessionStorage`, a cookie the page can read, a variable — is readable by any script that
reaches the page. And a single-page application reaches for scripts constantly: a dependency,
a dependency's dependency, a browser extension, a cross-site scripting hole in one component.

So the question is not "how do we stop script from running on our page", which nobody has ever
managed permanently. It is: **when script does run on the page, what can it take away?**

## Decision

**The access token lives in a closure, for the fifteen minutes it is good for. The refresh
token lives in a `HttpOnly` cookie the page cannot read.**

Concretely:

- Signing in and refreshing both return the pair in the body, exactly as before, and both now
  also set `mizan_refresh` — `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/tokens`.
  The console reads the access token out of the body and deliberately drops the refresh token
  on the floor.
- A refresh sends no body at all. The cookie is the credential.
- A body that does name a token still wins over the cookie, so nothing that already worked
  stops working, and a client that named a token never has a different one spent for it.
- Signing out is an endpoint, not a local forget. It revokes the whole family and clears the
  cookie.

**What this buys.** Script on the page can *use* the session while the page is open — that is
unavoidable and always was. What it cannot do is copy the credential and use it tomorrow from
somewhere else. That is the line between an incident and a breach, and it is the only line
available here.

**Why the path is `/api/v1/tokens`.** Every other request carries an access token in a header,
so the refresh token has no reason to travel with them. A credential attached to every request
is a credential in every proxy log.

**Why `SameSite=Strict`.** It is also the CSRF defence for the refresh endpoint. A cookie a
browser will not attach to a cross-site request cannot be spent by a form on somebody else's
page, which is what makes a cookie-carried credential safe to accept on a `POST` at all.

**Why the console and the API are one origin.** A cookie only comes back to the origin that set
it. So the console is served by a proxy that forwards `/api/` to the gateway, in development
through Vite and in Compose through nginx. This is not a convenience: without it the session
would not survive a reload, which is the entire thing the cookie is for.

**Why the access token is not persisted at all.** Losing it on reload costs one request, and
that request is the same refresh a fifteen-minute-old session needs anyway. There is one code
path for "renew this session", used by both, so there is one thing to get right.

## What was rejected

**A refresh token in `localStorage`.** The common answer, and the reason this ADR exists. It is
readable by any script on the page, survives forever, and is exfiltrated in one line. The usual
defence — "you have bigger problems if you have XSS" — is true and beside the point: with a
cookie, the bigger problem ends when the tab closes.

**An access token only, with no refresh at all.** Signing in every fifteen minutes. Honest, and
nobody would use the console.

**A long-lived access token.** Nothing revokes an access token; its lifetime is the window in
which a change of roles has not taken effect. Lengthening it to avoid refreshing trades a
security property for a convenience one and hides the trade.

## Consequences

- The console cannot be hosted on a different origin from the API without changing this
  decision. That is a real constraint and a deliberate one.
- One renewal at a time, enforced in the console. A refresh token is single use and replaying
  one revokes the family — so two panels refreshing in parallel would spend two tokens, the
  platform would correctly see a replay, and the person would be signed out for opening two
  things at once. The single-flight is not an optimisation; it is what stops the console
  triggering the platform's own theft detection.
- A failed renewal signs the person out rather than leaving them holding a token that no longer
  renews. A half-signed-in state fails on every page separately instead of once, clearly.
- `mizan.security.session-cookie.secure` defaults to true and is turned off in the local Compose
  stack, which speaks plain HTTP. That is the one place it is right to turn off, and it is
  written down here so it stays that way.
