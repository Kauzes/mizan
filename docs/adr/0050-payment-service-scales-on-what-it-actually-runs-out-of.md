# ADR 0050: Payment service scales on what it runs out of, inside a connection budget

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-85

## Context

The chart from MIZ-83 ran two replicas of every service, and nothing had asked what that costs
the one thing they all share. The answer, measured on the running stack and then counted:

- Every service that owns a database uses Hikari's default pool of ten, and Hikari opens the whole
  pool when the pod starts. One replica each already held 60 of Postgres's 100 connections, 69 with
  monitoring attached.
- Two replicas each is 120. **The chart as merged could not start all of its pods against a default
  Postgres**: the last ones to start would be refused with "too many clients". An autoscaler on top
  would have added ten more per pod at exactly the moment traffic was highest.

The second question was what "load" means for the payment service, and the code answers it.
`authorize` is one transaction that loads the payment, asks risk and then the acquirer, and
commits. Open-in-view is off, but the transaction itself holds its connection across both calls —
up to half a second on risk and five on the acquirer. **A payment-service pod runs out of database
connections long before it runs out of CPU**: ten authorizations waiting on a slow acquirer is a
saturated pod with its processors idle. An autoscaler watching CPU would stay put while requests
queued for a connection.

The third was whether more than one replica is even safe. Three of payment-service's four scheduled
sweeps take no lock on the rows they work on, so two pods run each of them over the same rows at
the same time.

## Decision

**Every pod the chart can run fits inside a written connection budget. Payment service autoscales
between two and six replicas, on CPU by default and on connections in use where the cluster can see
them. Running the sweeps twice is proven harmless rather than locked.**

- **The budget is a value.** `maxConnections: 100` and `reservedConnections: 10`, leaving 90. Every
  database-owning service gets a pool of five and an idle floor of two. At the autoscaler's maximum
  the chart opens 80. `ChartTest` counts replicas times pool for every service — at the autoscaler's
  maximum, not its minimum — and fails when it no longer fits. It was written first and failed on
  the chart as it stood, with the breakdown that sums to 120.
- **Two to six replicas.** Two is the floor for surviving a pod's loss. Six is the ceiling the budget
  allows at five connections each; more pods needs more Postgres first, and the values comment says
  both change together.
- **CPU at 60 percent by default**, because every cluster has metrics-server and a metric the
  autoscaler cannot read scales nothing. **Connections in use is the signal that means load** —
  scale when four of five are busy on average, before requests start waiting for the fifth — and it
  is one value, `connectionsInUse.enabled`, once a metrics adapter exposes Micrometer's
  `hikaricp_connections_active`. MIZ-86 installs one on kind.
- **Up at once, down slowly.** Scale-up has no stabilisation window and adds two pods at a time.
  Scale-down waits five quiet minutes and removes one pod a minute, because payment traffic comes in
  bursts and a pod removed between two of them is a cold JVM and a new pool for the second.
- **The autoscaler owns the count.** A Deployment with an autoscaler renders no replicas field, so
  `helm upgrade` does not reset what the autoscaler chose and shrink the service mid-deploy.
- **The sweeps are not locked, because running them twice is already safe, and now proven.** The
  refund sweep is the one that moves money, and three things stop it moving twice: the acquirer
  answers a repeated refund reference with what it already did, the ledger posts once per external
  reference (MIZ-36), and the refund's version column refuses the second write. New tests release two
  sweeps at the same moment: two pods resolving one unknown authorization decide it once, and two pods
  finishing one interrupted refund reverse the money once and leave the books balanced.

## Consequences

- **Six pods at five connections is thirty concurrent authorizations for the platform** against a
  default Postgres. That is the real capacity of this configuration and it is written down rather
  than discovered. The fix that moves the ceiling is not more pods: it is not holding a connection
  across the acquirer call, or a connection pooler in front of Postgres. Either is its own story.
- A pool of five caps one pod at five authorizations waiting on the acquirer. Past that, requests
  wait for a connection, which is exactly what the connections-in-use metric scales on.
- Duplicate sweeps still do duplicate work: a second acquirer lookup, a second refund call answered
  from the acquirer's own record, an optimistic lock failure logged at debug. At two to six pods
  that is noise, not load. Claiming rows with `skip locked`, as the outbox relay does, is the upgrade
  if it ever becomes load.
- Scaling up and back down on a real cluster under generated load is shown in MIZ-86, on the same
  kind cluster and the same load as the rolling deploy. Nothing in this story could honestly claim
  it without a cluster.

## Alternatives

- **Leave Hikari at ten and raise Postgres to 200.** Moves the wall without writing it down, and every
  connection is memory on the database server. The budget has to exist either way.
- **Scale on CPU alone.** Stays put during the saturation this service actually has.
- **Scale on request rate.** Better than CPU, and it cannot tell a hundred fast requests from a
  hundred stuck on a slow acquirer. Connections in use can.
- **Lock every sweep with an advisory lock or `skip locked`.** Removes duplicate work at the cost of
  holding a lock or a transaction across remote calls, to fix a problem the idempotency already
  solves. The tests are what earned leaving it alone.
