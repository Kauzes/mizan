# ADR 0040: The gateway's actuator moves to a port the edge does not serve

- Status: accepted
- Date: 2026-09-13
- Jira: MIZ-75

## Context

Every service on this platform exposes its metrics at `/actuator/prometheus`, and for seven of
them that is already private: nothing routes to those ports from outside, and the gateway
forwards the merchant API and a short list of health and documentation paths, nothing else.

The gateway is the exception, because the gateway *is* the edge. Its own actuator sits on the
same port it serves merchants from. The authentication filter refused the scrape with a 401,
which was the first honest signal that something was wrong with the arrangement — and the two
obvious fixes are both bad:

- **Make `/actuator/prometheus` public.** Request rates, upstream latencies, error counts by
  route and JVM internals, readable by anybody who can reach the API. Not catastrophic, and not
  nothing: it is a live description of what this platform is doing and how it is built.
- **Give Prometheus a credential.** Then a secret lives in a scrape configuration file, gets
  committed, and the monitoring system holds a platform credential whose only purpose is to read
  numbers.

## Decision

**The gateway's actuator listens on `8090`, which is not published outside the Compose network,
and the authentication filter does not guard that port.**

- **Health stays answerable from the edge**, through an ordinary gateway route that forwards
  `/actuator/health` to the management port. Whether the platform is up is not a secret, and
  the thing asking is usually a load balancer holding no credentials. That was already the rule
  in the public route list; this keeps it true.
- **The exemption is about which door the request came in by, not about the path.** The filter
  compares the port the request arrived on with the configured management port, and the same
  path on the edge port is refused exactly as before.
- **And it disappears if the two ports are ever the same.** A deployment that puts the actuator
  back on the edge port gets no exemption, because the exemption exists only for the reason that
  the port is unreachable from outside.

## Consequences

- The gateway has two listeners, and a deployment has to keep the second one unpublished. In
  Compose that is the absence of a `ports:` entry; in Kubernetes (MIZ-12) it is a container port
  that no Service exposes, and that is where this decision will need to be made again on
  purpose.
- Anything inside the platform's network can read the gateway's metrics without a credential,
  which is the same trust level every other service's actuator already has here. The network
  boundary is doing that work, and it is worth saying plainly rather than pretending otherwise.
- One more thing a test has to hold in place: the scrape configuration names `gateway:8090`
  while the service listens for merchants on `8080`, and a check that read `server.port` alone
  would have quietly scraped the wrong port.

## Alternatives

- **Scrape through the API port with a service credential.** Puts a secret in a monitoring
  config and gives Prometheus an identity on the platform, for read-only numbers.
- **Expose it publicly and accept the leak.** Cheap, and the kind of decision nobody revisits
  until the information is used against the platform.
- **No scrape of the gateway at all.** The gateway is where a merchant's request first arrives
  and where an upstream's latency first shows. A monitoring system blind to the edge is blind to
  the half of the platform users actually touch.
