# ADR 0025: An event nobody can handle blocks nobody, and is visible to somebody

- Status: accepted
- Date: 2026-09-03
- Jira: MIZ-50

## Context

Some event will be unprocessable: a bug in a handler, a payload from a version the consumer
does not understand, a downstream that is not slow but gone. MIZ-49 left that case open and
said so — a handler that throws leaves no record and the event is redelivered, which is right
for a transient failure and a trap for a permanent one. Because ordering within a payment is
preserved, a permanently poisoned event is a permanently stalled stream.

There are two defaults systems arrive at by accident and both are wrong. Retrying forever
blocks the partition and takes every well formed event behind the bad one with it. Dropping
loses something that mattered, silently.

## Decision

**Bounded retry, then set the event aside.** A few attempts with a growing delay, then the
message goes to a dead letter topic and the listener moves on. That is the arrangement that is
neither of the two wrong defaults.

**A handler may declare a failure hopeless.** `UnprocessableEventException` means "this will
fail identically forever", and such a message is not retried at all. A message that cannot be
parsed is the clearest case: it will not parse differently in a second, and retrying it is not
caution but a busy loop while everything behind it waits. Anything else thrown is assumed
retryable, which is the safe default — the cost of retrying something hopeless is a delay, and
the cost of not retrying something transient is an event nobody handles.

**Retries happen in the listener, so the partition waits during them.** Deliberate, and the
price of ordering: the consumer cannot cheaply tell which of the messages behind this one
belong to the same payment, and letting a later event of the *same* payment past would be
worse than a short delay for everyone. The bound is what keeps the wait short.

**The dead letter topic is consumed into a table.** The topic alone satisfies the machine and
not the person: it unblocks delivery, and it leaves the evidence somewhere nobody looks until
they already know to. What an operator needs to ask is "what is broken, why, and how much of
it", and that is a query. Both exist for a reason.

**The row keeps what makes it actionable**: the exception and its message rather than a stack
trace, the attempt count, the correlation id of the request that caused the event several
services ago, and the original message byte for byte. Not a summary — redelivering a
reconstruction would redeliver this platform's idea of the event rather than the event.

**Redelivery republishes to the original topic under the original key**, so it arrives exactly
as an ordinary delivery does and goes through the same inbox. A redelivery down a path nothing
else uses would be a path tested only on the one event already known to be difficult. The
original key matters: a different one would put the event in a different partition and lose the
ordering that was the point of keying it.

**A dead letter is marked redelivered, not deleted.** What went wrong and how often is the
useful part, and a table that forgets its failures the moment somebody retries them cannot
answer whether the retry helped.

**An event that dead letters twice is one row with a count.** Redelivering before the bug is
actually fixed is the ordinary mistake, and a growing pile of rows would hide how many distinct
things are wrong.

**It is loud.** Setting an event aside logs at error, because something downstream is now
missing information it was promised, and nobody finds that out from a debug line.

**The dead letter listener writes a row and nothing else.** No work that could throw the way the
handler it exists for did. It reads the event's id and type from headers first and falls back to
the body, because the body may be exactly what could not be parsed. A dead letter handler that
dead letters is a hole with no bottom.

## Consequences

**A message with no readable event id cannot be deduplicated across failures.** It gets a
generated id so an operator can still see it, count it and read it, but a second dead lettering
of the same unreadable bytes is a second row. This was found by a test asserting otherwise; the
test was wrong about what the design can offer, and the alternative — hashing the payload for an
identity — is machinery for the case that matters least.

**Nothing expires the table.** A dead letter is evidence and stays until somebody deals with it,
which is correct, and means an ignored one is there forever. That is the intended pressure.

**Redelivery is one event at a time.** There is no "retry everything", deliberately: the usual
cause of a hundred dead letters is one bug, and a button that replays all of them before it is
fixed produces two hundred.

**The endpoint is an actuator endpoint, not an API route**, for the same reason the ledger's
integrity check is: this is a question about the service rather than about one merchant's data,
and there is no merchant who should be asking it. It is reachable only through the gateway's
internal route, which needs a token.

**Retry counts are configuration, and the right values are not known yet.** Three attempts over
a few seconds is a guess that suits a handler writing a row. A handler calling somebody else's
API wants different numbers, and will want them per listener rather than per service.

## Alternatives considered

**Retrying forever.** No lost events, and one poisoned message stops a partition indefinitely
while every well formed event behind it waits. This is the default that looks safest and is the
most dangerous.

**Dropping after the retries.** Simple, and it loses money-related information with no record
that it existed. The whole outbox exists to prevent exactly that.

**Non-blocking retry on separate topics** (Spring's `@RetryableTopic`). Retries do not hold the
partition, so throughput survives a slow failure — and events lose their order relative to
their own aggregate, because a retried one arrives after ones published later. For payment
events that trade is the wrong way round. It would be right for a webhook delivery, where each
message stands alone, and Epic 8 should look at it again.

**A dead letter table with no topic**, writing the failure straight to the database from the
error handler. Fewer moving parts, and it makes the recovery path depend on the database being
healthy at the moment something is already failing. The topic holds the message even when this
service cannot.

**Deleting on redelivery.** Tidier, and it destroys the history that answers whether the fix
worked.
