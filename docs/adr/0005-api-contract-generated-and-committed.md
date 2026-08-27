# ADR 0005: The API contract is generated from the code, and committed

- Status: accepted
- Date: 2026-08-27
- Jira: MIZ-25

## Context

An API that seven services publish needs a written contract, and a written contract has one
failure mode: it stops matching the code. A hand written specification drifts the first time
someone ships a field and forgets the document. A specification that exists only at runtime
never drifts but also never appears in a review, so nobody sees a breaking change until a
merchant does.

There is a second problem. Every service returns the same errors, carries the same
correlation id and will accept the same credentials. Described service by service, those
descriptions diverge, and a merchant integrating against two services reads two contracts.

## Decision

The specification is generated from the code by springdoc, exported to `docs/api` by
`./gradlew exportOpenApi`, and committed. A test in every documented service compares the
committed file against the one the running service generates, so a change to an endpoint
that is not exported fails the build.

The shared half of the contract is contributed once, from `common-web`: the problem detail
schema, one response component per `ErrorCode`, the correlation id header, and the
authentication schemes. An operation documents a failure by naming the code it can return.

The gateway publishes no specification of its own. It serves Swagger UI listing every
service's spec, fetched through the internal routes, so there is one address for reading the
whole platform.

## Consequences

An API change now shows up twice in a pull request: the code, and the spec diff. That is the
point. It also means a reviewer can see a breaking change as a removed field rather than
having to infer it.

Forgetting to export is not a silent mistake, but it is a mistake that fails CI rather than
being fixed for you. The message names the task to run.

The code is the source of truth and the file is a rendering of it, so the file is never
edited by hand. Anything wanted in the spec has to be expressible from the code, which is a
real constraint: prose that belongs to no operation has nowhere to live except the shared
description.

The authentication schemes are described before anything enforces them. They say so, in the
spec itself. MIZ-2 makes them real, and until then a caller reading the spec learns what is
coming, not what is checked. Header names and signing details may change when that lands.

Every documented service now boots a second application context in its tests, for the spec
comparison, which costs a few seconds per service in the build.

## Alternatives considered

**Design first: write the spec, generate the code.** The contract stops being a rendering of
the implementation and starts being its source, which is the stronger position for an API
several teams consume. It also introduces a code generator, generated sources in the build,
and a second place to look when the two disagree. Worth revisiting if the merchant console
and the Android app end up wanting a generated client, which is the point where a generator
pays for itself.

**Generate at runtime only, commit nothing.** Nothing to keep in sync, and nothing to review
either. A breaking change would reach a merchant before it reached a reviewer.

**The springdoc Gradle plugin.** It boots the application to fetch the spec, which now means
starting a database as well, and gives the build a second way to start a service alongside
the one the tests already use. The test that compares the spec is the same test that writes
it, so the export cannot drift from the check.
