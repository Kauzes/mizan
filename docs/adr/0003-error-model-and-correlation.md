# ADR 0003: RFC 9457 problem details with a stable code, and one correlation id per request

- Status: accepted
- Date: 2026-08-25
- Jira: MIZ-22

## Context

Seven services will each fail in their own way unless the shape is decided once. Two
audiences need different things from the same response. A merchant integration needs a
value it can branch on that will not change when we reword a message. A person debugging
needs to find every log line belonging to one request, across every service it touched.

There is also a leak risk. The default behaviour of an unhandled exception is to surface a
class name, sometimes a stack trace, and occasionally a database message, which tells an
attacker about internals and tells a merchant nothing useful.

## Decision

Every error response is an RFC 9457 problem detail, carrying the standard `type`, `title`,
`status`, `detail`, plus three extensions: `code`, `correlationId`, and `timestamp`.
Validation failures add an `errors` array of field and message pairs.

`code` comes from a closed enum. Codes are the contract: once shipped, a code is never
renamed and never reused for a different meaning. HTTP status is derived from the code, so
the two cannot disagree.

Only exceptions extending `MizanException` produce a detailed response. Everything else is
reported as `INTERNAL_ERROR` with a fixed message and logged in full on the server side.

A correlation id is attached at the edge, generated when the caller does not supply one,
echoed on the response, propagated on outbound calls, and carried across Kafka as a message
header.

## Consequences

Adding an error case means adding an enum constant, which is a deliberate act that shows up
in review. That friction is the point.

An inbound correlation id is attacker controlled and ends up in log files, so it is
accepted only when it is short and alphanumeric, and replaced with a fresh id otherwise.
Without that, a caller could inject newlines and forge log lines.

The servlet filter keeps the id in the logging context, which is a thread local. The
gateway is reactive, where a thread local does not survive the hops, so there the id is
stamped onto the forwarded request instead and the gateway's own log lines do not carry it.
Closing that gap properly needs context propagation, which arrives with tracing in MIZ-11.

## Alternatives considered

**A custom error envelope.** One more bespoke shape for integrators to learn, and no
tooling understands it. Problem details are a standard and Spring builds them natively.

**HTTP status alone as the contract.** Too coarse. Two different 409s mean different things
to a merchant, and a status code cannot tell them apart.

**A trace id from the tracing stack instead of our own header.** That couples correlation to
an observability tool that is not installed yet. The header is cheap and will sit alongside
the trace id rather than compete with it.
