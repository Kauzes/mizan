# ADR 0004: A database per service, with the schema owned by forward only migrations

- Status: accepted
- Date: 2026-08-27
- Jira: MIZ-23

## Context

Seven services share one Postgres. Something has to decide who owns which tables, and
something has to decide who is allowed to change them. Left undecided, the shortest path is
a single database that every service reads, which quietly turns the platform into one
application with seven deployment units.

The schema also needs one way to change, and the same way everywhere. A schema built by
hand locally, by a script in CI, and by whatever the last person ran in a cluster is three
schemas that agree until the day they do not.

There is a specific hazard in the stack we chose. Hibernate will happily generate a schema
from entities, which is convenient until a rename drops a column of live money.

## Decision

Each service owns one database on the shared Postgres and connects to no other. The
databases are `identity`, `ledger`, `payment`, `risk` and `notification`; `gateway` and
`bank-simulator` hold no state.

Schema changes are Flyway migrations in the service's own `db/migration`, named
`V<number>__<description>.sql`, numbered from one, applied in order when the service starts.
They are forward only: once a migration has been applied anywhere, it is never edited, and a
correction is a new migration with the next number.

Hibernate never generates schema. `ddl-auto` is `validate`, so an entity that disagrees with
the migrations stops the service at startup instead of reshaping a live database.

Credentials and the database url come from configuration, and the compose stack passes the
same values it starts Postgres with.

## Consequences

Actuator health now reports the datasource, so a service whose database is unreachable fails
its container healthcheck rather than reporting itself up while serving errors.

Migrations run at startup, which means a service cannot come up before Postgres is ready.
Compose waits for a healthy Postgres before starting anything that migrates.

A migration that has been applied is immutable, including on a developer's machine. Changing
one during development means throwing the local database away with `docker compose down -v`
rather than editing the file and hoping. Flyway's checksum will catch the edit either way.

Every service test now needs a real database, so the tests that prove a service starts are
integration tests and Docker is needed to run them. `-PfastTests` no longer covers a service
starting up. Test tasks that start containers take turns rather than racing for the Docker
daemon, and the full build is slower for it.

Nothing enforces the boundary between databases at the Postgres level yet: the services
share one set of credentials locally, so a service could technically connect to another's
database. Separate roles per database arrive with the deployment work in MIZ-12.

## Alternatives considered

**One database with a schema per service.** Cheaper to run and one connection pool, but a
join across two services' tables stays one word away, and the first person under deadline
pressure writes it.

**Hibernate `ddl-auto: update`.** No review, no ordering, no way to express a data migration,
and no answer for a column that has to be dropped. It is a prototype tool.

**Liquibase.** A changelog format that abstracts over SQL, which is worth paying for when
several database engines have to be supported. Only Postgres is supported here, so the
abstraction buys nothing and costs the ability to read a migration as the SQL it runs.

**Migrating from a separate job rather than at startup.** The right answer when a rolling
deploy has to coordinate schema and code, and it is what MIZ-12 will need to revisit. Today
a service migrating its own database is one moving part instead of two.
