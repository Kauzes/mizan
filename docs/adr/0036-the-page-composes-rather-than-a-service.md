# ADR 0036: The page that needs three services asks three services

- Status: accepted
- Date: 2026-09-10
- Jira: MIZ-62

## Context

The payment detail page is the first screen in this console that cannot be answered by one
service. It shows what happened to a payment (payment-service), what that wrote in the books
(ledger-service), and what the merchant's own endpoints were told about it
(notification-service).

The usual answer is a backend for frontend: one endpoint that fans out and returns the page
already assembled. It is one round trip instead of four, and the browser stops knowing which
service owns what.

## Decision

**The page asks each service directly.**

- **A composing endpoint is a service that knows about everything.** It would have to know the
  shape of a payment, the shape of an entry, and the shape of a delivery, and it would break
  whenever any of the three changed. Right now no service on this platform knows the internals
  of another; the one place that reaches across, risk learning from payment events, does so
  through published events rather than through a database or a shape. A composing service would
  be the first exception, added for a page.
- **It would live somewhere awkward.** In the gateway, it makes the edge stateful and
  domain-aware, and the gateway is deliberately the one component with no opinions. As its own
  service it is a new deployment whose only job is to save a page three requests.
- **The composition belongs where the question is asked.** This page is the only caller that
  wants all three, and it is the only thing that knows why. When a second caller wants the same
  three, that is the moment to reconsider — and this ADR is where to start.
- **The round trips are cheap here.** They are same-origin, they share a connection, the ledger
  and delivery calls run after the payment is known and can run beside each other, and each
  section paints as it arrives instead of the page waiting for the slowest.

**Each section is fetched only if the person may read it.** A viewer without `ENTRY_READ` never
sees the books requested and never sees a panel refusing to load one. A panel that renders a
refusal is a panel that has told somebody the thing exists.

**Deliveries got a new route rather than a filter on an old one.** The existing list is scoped
to an endpoint, which answers "is this endpoint working". A merchant debugging one order does
not know which of their endpoints to look in, so `GET /webhook-deliveries?paymentId=` answers
by payment instead. Each row names its endpoint, so the attempts behind a delivery are still
read through that endpoint's own route: two views answering the same question differently is
one view too many.

## Consequences

- A slow ledger makes the books section slow and leaves the rest of the page working. With a
  composing endpoint, a slow ledger makes the page slow.
- The console knows the platform's service boundaries. That is a real coupling, and it is the
  same coupling every published API client has: these are the routes this platform documents.
- If a second consumer needs the same composed view — the Android app in MIZ-14 is the obvious
  candidate — the decision changes and this ADR is the place to say so. One caller is a page;
  two callers are a contract.
