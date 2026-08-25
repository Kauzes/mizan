# ADR 0001: Split into services rather than a modular monolith

- Status: accepted
- Date: 2026-08-25
- Jira: MIZ-1

## Context

A payments platform can be built as one deployable with clean internal modules, and for a
system of this size that would be the pragmatic choice. It would be simpler to run,
simpler to test, and would avoid distributed transactions entirely.

The goal here is not only a working payments platform. It is to build and operate the
failure modes that only appear once a system is distributed: partial failure mid flow,
duplicate delivery, out of order events, and compensation when a downstream step cannot be
rolled back.

## Decision

Seven independently deployable Spring Boot services with a database per service, one
shared library for contracts only, and Kafka between them. Synchronous HTTP where a caller
needs an answer now, events where it does not.

## Consequences

Payment and ledger cannot share a transaction, so payment capture is a saga with a
compensating entry rather than a rollback. That is the point, but it means every write
path needs an idempotency key, and correctness under retry must be proven by tests rather
than assumed.

Local development needs Docker. A one command startup is therefore a hard requirement, not
a convenience: `docker compose up` has to bring the whole platform to a healthy state or
the project is unusable to anyone else.

## Alternatives considered

**Modular monolith with an in process event bus.** Simpler and defensible, but it removes
the distributed failure handling that this project exists to demonstrate.

**Serverless functions.** Cold starts and per function state make a ledger with strict
consistency awkward, and it ties the project to one cloud vendor.
