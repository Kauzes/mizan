# ADR 0043: Every service traces everything, and one collector decides what is kept

- Status: accepted
- Date: 2026-09-14
- Jira: MIZ-78

## Context

A payment on this platform touches the gateway, the payment service, the risk scorer, the
ledger, an acquirer, and then — minutes later, through a topic — the notification, risk and
settlement services again. When one of those is slow or wrong, the question is which, and until
now the only way to answer it was to read six logs and line them up by timestamp.

Tracing answers it directly, and the whole value depends on two decisions that are easy to get
quietly wrong.

**The first is sampling.** Keeping every trace is not affordable in a real deployment, and the
obvious place to sample is in the service: set a probability, export a tenth of them, done. That
is head sampling, and it has a property nobody notices until an incident. The decision is made
at the first span of a trace — before the request reached the acquirer, before the timeout,
before the error. A tenth of traces kept means a tenth of *failures* kept, chosen at random. The
setup records the days when nothing happened and loses the hour that mattered.

**The second is the topic.** Every other link in a trace is made by a header on a request that
is in flight. The outbox is not like that. The event is written inside the caller's transaction;
the relay publishes it later, from a scheduler, on a thread with no relation to the request; the
consumer picks it up in another process. By the time anything could add a header, everything
that knew the trace is gone. Left alone, a trace stops dead at the topic, and the consumer's
work appears as an unrelated orphan — which is precisely the hop nobody can reconstruct by hand,
and therefore the one worth the most.

## Decision

**Every service exports every span to a collector, and the collector alone decides what is
kept. The outbox row carries the trace across the topic.**

- **Services sample at 1.0.** Not because everything is kept, but because the service is the
  wrong place to decide. It cannot know what it does not yet know.
- **The collector tail samples**: it holds a trace until it is finished and then applies three
  policies, any one of which keeps it — anything whose status is `ERROR`, anything slower than a
  second, and a percentage of everything else. Only that last one is a volume control, and it is
  the only one a deployment turns down. The rule is therefore *an error is always kept*, and it
  survives whatever the percentage is set to.
- **The outbox stores a W3C `traceparent` with the event**, written in the same transaction as
  the event and the state change. The relay puts it on the message as a standard `traceparent`
  header, and the consumer's own tracing library continues the trace without any code here
  knowing about it. The column is checked by a constraint, because a malformed value is silently
  ignored by every consumer and shows up only as a trace that stops for no reason.
- **The correlation id and the trace id are connected in both directions**, and neither replaces
  the other. The correlation id goes onto the span as an attribute, so a trace can be found from
  an id somebody read out over the telephone. The trace id goes back on the response as
  `X-Trace-Id`, and onto each step of a payment, so a trace can be found from a request or from
  a row in the console.
- **A span attribute is not an observation key value.** The correlation id is per trace and
  unbounded; as a key value it would become a metric label too, which is the exact thing ADR
  0041 keeps out of the monitoring system.

## Consequences

- Every service sends every span over the network whether or not it will be kept. On this stack
  that is nothing; in a deployment it is real traffic and a real collector to run. The trade is
  deliberate: it buys the only sampling rule worth having.
- The collector is a single point through which all tracing flows. If it is down, traces are
  lost — not requests, because export failures do not fail the work that produced them.
- A trace that crosses the outbox has a visible gap in it, between the request that recorded the
  event and the relay that published it. That gap is real. It is how long the row waited, and
  seeing it is a feature.
- `decision_wait` is ten seconds, so a trace is not searchable the instant the request finishes.
  Somebody pasting a trace id in immediately will be told there is nothing there. Worth knowing
  before it is reported as a bug.
- The local stack keeps a hundred percent of everything, so the percentage policy is never
  exercised here. What is exercised is that the error and latency policies exist and are
  independent of it, which is the part that would break silently.

## Alternatives

- **Head sampling in the services, no collector.** One fewer process, and it cannot keep the
  failures. Everything else about this decision follows from rejecting it.
- **Head sample, but always sample when an error is already known.** Works for an error the
  first service can see immediately, and not for the common case: a failure three hops in, long
  after the sampling decision was taken.
- **Export straight to Tempo and tail sample there.** Tempo stores and queries; the sampling
  decision would then have to be somewhere else anyway, or not made at all.
- **Propagate the trace by putting the trace id in the event payload.** Then every consumer
  needs code to read it and start a span, on this platform and on anybody else's. A standard
  header is read by libraries that already exist.
- **Skip the outbox hop and accept orphan consumer traces.** Cheapest, and it throws away the
  one link a person genuinely cannot reconstruct from logs and timestamps.
