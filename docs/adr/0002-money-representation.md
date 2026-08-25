# ADR 0002: Money is minor units in a long

- Status: accepted
- Date: 2026-08-25
- Jira: MIZ-1

## Context

Amounts cross service boundaries as JSON, are stored in Postgres, are summed in the
ledger, and are displayed in two frontends. Any representation that can lose or invent a
fraction of a kurus will eventually put the ledger out of balance, and the reconciliation
job will report a drift nobody can explain.

## Decision

A `Money` value type in `common` holding a signed `long` of minor units and an ISO 4217
currency code. It serializes as `{"amount": 12550, "currency": "TRY"}`, meaning 125.50 TRY.
Postgres stores `BIGINT` plus `CHAR(3)`. Arithmetic between different currencies throws.

## Consequences

Formatting for display becomes the frontend's job, which is where locale belongs anyway.

A `long` caps a single amount near 92 quadrillion minor units, far beyond anything this
platform will hold, so overflow is only a real risk when summing balances. Balance
aggregation checks for it explicitly.

Division, which appears when splitting fees, cannot be exact. Splits use a largest
remainder allocation so the parts always add back to the whole.

## Alternatives considered

**`BigDecimal`.** Exact, but its scale travels with the value, `equals` compares scale, and
JSON round trips through `double` in some clients. It stays out of transport and storage.

**Floating point.** Not viable for money.
