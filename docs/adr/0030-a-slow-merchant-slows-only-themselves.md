# ADR 0030: A slow merchant slows only themselves

- Status: accepted
- Date: 2026-09-06
- Jira: MIZ-55

## Context

Delivering to endpoints outside our control is a different reliability problem from delivering
between our own services. A merchant's endpoint can be slow, wrong, unreachable, or answer 200
to everything without reading it, and none of that is something this platform can fix or
predict.

The requirement that decides the design is the third acceptance criterion: **a slow or failing
endpoint cannot delay delivery to any other merchant.** Everything else here follows from it.

## Decision

**Three things together earn that property, and removing any one loses it:**

1. Work is claimed with `for update skip locked`, so a worker holding a delivery to an endpoint
   that never answers holds one row and every other worker steps over it. Without this they
   queue, and one broken merchant stops the platform telling anybody anything.
2. The claim commits *before* the call is made. A database transaction open across a request to
   somebody else's server, for as long as they feel like taking, is how a connection pool is
   exhausted by one merchant.
3. Every call has a timeout. Without one, "cannot delay anybody else" would depend on every
   merchant's server behaving, which is exactly the thing this platform does not control.

**A virtual thread per attempt, and no pool size to configure.** These spend nearly all their
time waiting on somebody else's server, which is the shape virtual threads are for. A fixed pool
would make "how many merchants can we talk to at once" a number somebody has to guess, and
guessing it low is how one slow endpoint starts delaying everybody after all. The batch size
bounds the work instead, and means something plainer: how much one pass takes on.

**The body is built once and stored, not rebuilt per attempt.** A signature covers a body, and a
body rebuilt from the same data by the same code can still differ by a field order or a
timestamp — at which point the retry carries a signature for something else and a merchant
checking properly rejects it. Storing the bytes also means every attempt of one delivery is
byte-identical, which is what lets the merchant treat repeats as repeats.

**The delivery id is the same on every attempt**, in a header and in the body. At-least-once is
the promise here as everywhere else on this platform, and this is the handle that makes it
survivable from the receiving end. It is in the body as well as the header because a merchant
storing what they received should be able to deduplicate from the thing they stored.

**Deliveries are queued in the same transaction as the notification that caused them.** The
outbox lesson from the other side: a notification with no deliveries is a merchant who is never
told, and a delivery with no notification is a merchant told about something this platform does
not believe.

**Every attempt is recorded, not only the last.** A merchant debugging their endpoint wants to
see 502 at 10:00, a timeout at 10:00:04 and a 200 at 10:00:12. Keeping only the last answers "is
it working now", which is the one question they can already answer themselves. Duration is
recorded too, because "slow" and "broken" are different problems.

**A null status code rather than a made-up one.** "Nothing answered" and "answered 500" are
different facts and a merchant should be able to tell which they had.

**Bounded attempts, then the delivery is `FAILED` and visible.** Eight attempts with the backoff
spans about five hours, which is more than the "down for an hour" the epic asks about. A failed
delivery is the dead letter: it is listed, it says why, and it can be sent again from attempt
zero with the same body and the same id.

**Retries block nothing.** MIZ-50 retried inside the listener and held the partition, which was
right there because ordering within a payment mattered. It is wrong here: each webhook stands
alone, so a backed-off delivery simply is not due and every other delivery flows past it. ADR
0025 said this story should look again at that trade, and this is the answer.

**The destination is checked again at delivery time**, through the same class registration uses,
because DNS answers to whoever controls it and a check that only ran at registration is one an
attacker waits out.

## Consequences

**`@Scheduled` did nothing, and no test noticed.** Notification-service had never scheduled
anything, so it had no `@EnableScheduling`. The dispatcher was a bean, was correct, and never
ran. Every test drove it directly and passed. The live check found it, by watching deliveries
pile up as `PENDING`.

That is the third time this platform has had a mechanism that exists and is not wired — after
MIZ-41's inactive idempotency and MIZ-47's unregistered auto-configuration — so there is now a
shared `ScheduledWorkTest` that fails when a service schedules work and nothing enables
scheduling. Verified by removing the annotation and watching it fail.

**Closing a stuck payment did not mean stop.** MIZ-53 gave an operator two verbs and implemented
them as one: both reset the attempt count, so a closed payment went back into the sweep and
became stuck again five attempts later. Found by the smoke check refusing to pass, and fixed the
way the refund already did it — closed is a separate fact from stuck.

**The https rule left the database.** It was enforced in three places and the third could only
produce a constraint violation — and it made the delivery path untestable, because a test needs
a merchant server this JVM can run and that is plain HTTP on loopback. A redundant check that
costs the tests for the thing it is redundant with is a bad trade. It is still enforced at
registration, where it produces a sentence a merchant can act on, and at delivery.

**There is an escape hatch, off by default and loud when on.** A test and a laptop demonstration
need to call loopback. Rather than let the tests reach around the check and prove nothing about
the real path, the check is switched off explicitly — and there is a separate test class,
without the flag, asserting it still bites.

**A merchant answering 200 without reading the body is indistinguishable from success.** Nothing
can be done about that from this side, and it is worth knowing rather than pretending otherwise.

## Alternatives considered

**Non-blocking retry topics in Kafka**, as ADR 0025 suggested looking at. They solve the same
problem — a retry that holds nothing up — and they would put the delivery history in a topic
rather than a table, when a merchant-readable history is a requirement and is a query. The table
was needed either way, and once it exists the topic adds a second system to reason about for no
property the table does not already have.

**One worker thread and a queue.** Simplest, and it makes every merchant wait behind the slowest
one, which is the requirement inverted.

**Delivering inline when the notification is decided.** No queue at all, and it puts a merchant's
webhook endpoint on the critical path of consuming a Kafka event — so a slow endpoint becomes
consumer lag, and then the outbox backs up behind it.

**Recording only failed attempts.** Smaller, and it removes the ability to answer "how long has
this endpoint been slow", which is the question a merchant with an intermittent problem actually
has.
