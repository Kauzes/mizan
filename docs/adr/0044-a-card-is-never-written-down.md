# ADR 0044: One place shapes a log line, and a card never appears on one

- Status: accepted
- Date: 2026-09-14
- Jira: MIZ-79

## Context

Logs are what is left when the metrics say something is wrong and the trace was sampled away.
This platform had eight copies of the same three lines of logging configuration, one per
service, and no check on what any of them wrote.

Two problems, and they are different in kind.

**The shape.** Eight copies is eight chances for the ninth service to be the one that forgets,
and a service whose lines are shaped differently from everybody else's is a service that falls
out of every search somebody runs during an incident. Worse, the lines that matter most are the
ones with no request behind them: a Kafka consumer's thread has never seen a request, and a
scheduled sweep's never will, so the lines about a merchant not being told and a batch not
closing were exactly the unattributed ones.

**The content.** A card number reaches a log through a line somebody added during an incident, a
`toString()` on a request object, an exception that quotes what it was given, or a framework
logging a body nobody asked it to. None of those looks wrong where it is written. A code review
catches most of them and eventually misses one, and a log store keeps what it is given for
months.

That last one is not hypothetical here. Writing the check found a real leak: Bean Validation
reports a failure by quoting the value it rejected, and Spring writes that rejection out, so a
mistyped card — or one an attacker was probing with — was in a log line verbatim.

## Decision

**One component decides the shape of every line, and a card is never on one.**

- **The format is contributed once**, by an environment post-processor in `common-web` that
  every service already depends on. A new service gets it by existing. Everything it sets is a
  default at the lowest precedence, so a service with a reason to differ says so and wins.
- **Two shapes, chosen by where it runs.** Readable by default, because the usual reader is a
  person watching a terminal and JSON read with the eyes is not a log but a puzzle. ECS JSON
  when `MIZAN_LOG_FORMAT` says so, which is what CI runs with and what a deployment sets — so
  the shape that ships is exercised on every pull request rather than first tried in anger.
- **Both ids on every line.** The correlation id is short, chosen here, and can be read out over
  a telephone; the trace id opens the trace (ADR 0043). Neither replaces the other and a line
  with one of them answers half the question.
- **Work with no request gets an id anyway.** A consumer takes the producer's id off the message
  header, so the line it writes and the line the payment service wrote are found by one search.
  A scheduled sweep gets a fresh id per execution — per execution, not per thread or per
  service, because what somebody wants when a run goes wrong is every line that run wrote and
  nothing from the run before it.
- **A card is validated by this platform's own code, not by an annotation.** Everything else
  here is validated declaratively and should be. The card is the exception, because declarative
  validation says no by quoting the value, and that is the one value that must never be written
  down. The message says the rule and never the value.
- **And it is asserted rather than intended.** A test drives real cards through an approval, a
  refusal, a void and a malformed request, then reads back every line the platform wrote and
  fails on anything that passes for a card — issuer digit, thirteen to nineteen digits, Luhn.
  The smoke check does the same over every line all eight services wrote during a whole run.

## Consequences

- The card is now checked in `PaymentService` rather than on the request record, which is
  inconsistent with every other field on the platform. That inconsistency is the point and is
  worth a comment at the site, which it has.
- The card-shaped scan will one day flag something that is not a card: an account number, a long
  identifier that happens to satisfy Luhn. The answer then is to look, not to loosen the rule.
  It is tuned to be quiet enough that a hit is worth reading — "any run of digits" flags every
  epoch timestamp, and a check that cries wolf is a check somebody deletes.
- Setting the log level of `org.springframework.web` to DEBUG in a deployment would again write
  request bodies. Nothing stops that, and nothing can; what the test guarantees is that the
  bodies themselves no longer hold a card by the time anything prints them.
- The first run in JSON found something readable text had hidden for months: notification-service
  and settlement-service each ran an outbox relay once a second against a table they do not own,
  and logged an ERROR every time. Having a database and a Kafka producer had been taken to mean
  having an outbox. Publishing is now opted into by the one service that owns an outbox, and a
  test reads the migrations to keep that true in both directions.
- Every scheduled run now writes an id that connects to nothing upstream, which is honest. What
  ties a relay's publish back to the request that recorded the event is the trace the outbox row
  carried, not the correlation id.

## Alternatives

- **A shared `logback-spring.xml` in `common-web`.** Also one place, and it gives up Boot's own
  structured logging — the ECS format, the MDC to fields mapping, the stack trace handling —
  all of which would then be written by hand here.
- **Leave the eight copies and add a test that they match.** Keeps the duplication and adds a
  test whose failure tells somebody to copy a file.
- **Redact at the appender**, with a pattern that masks anything card shaped on the way out.
  Tempting, and it makes every leak invisible rather than absent: the value is still built, still
  passed around, still in a heap dump, and the redaction is one regular expression away from
  missing a format. Better that the card is never in the string.
- **Keep `@Pattern` on the card and accept the echo.** It only reaches the log at DEBUG, which a
  deployment would not set. That is a rule about configuration protecting a rule about data, and
  the configuration is the part that changes at three in the morning.
