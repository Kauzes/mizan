# ADR 0029: A webhook endpoint is a URL somebody else chose

- Status: accepted
- Date: 2026-09-06
- Jira: MIZ-54

## Context

A merchant registers a URL and this platform promises to make requests to it. That is a
feature, and it is also a service that will fetch whatever URL it is handed — which is server-
side request forgery, and the usual first victim is a cloud metadata endpoint at 169.254.169.254
handing out credentials to anybody who asks.

The second problem is the secret. A merchant has to be able to prove a delivery came from us,
which needs a shared secret, which means this platform holds a credential belonging to somebody
else.

## Decision

**Only https, and only addresses on the public internet.** Loopback, the private ranges,
link-local, multicast, the wildcard address, carrier-grade NAT and IPv6 unique local addresses
are all refused. A delivery carries what a merchant was paid and is signed with a shared
secret; clear text gives away both.

**The check is on the resolved addresses, not on the hostname.** A hostname is not an address
until DNS says so, and DNS answers to whoever controls it. A name that resolves to 127.0.0.1 is
exactly the attack this exists to stop and looks like an ordinary hostname. Every address it
resolves to is checked, not the first, because a host that answers with one public address and
one loopback address is not a host this platform will call.

**And it has to happen again at delivery time.** DNS can change its mind between registration
and the call, so a check that only ran at registration is a check an attacker waits out. That
is MIZ-55's obligation and is written down here because it is easy to believe this story
finished the job.

**A URL may not carry credentials.** `https://example.com@internal.host/` reads as example.com
to a human skimming it and resolves to the other one.

**The JDK does not have a predicate for all of it.** `isSiteLocalAddress` covers 10/8, 172.16/12
and 192.168/16 and misses carrier-grade NAT at 100.64/10 and IPv6 unique local addresses at
fc00::/7. Both are private networks by any useful definition, and both are checked by hand.

**The secret is generated here, never taken from the caller.** A merchant-chosen secret is a
merchant-chosen password with the failure mode every password has. Thirty-two bytes, the same
length as the HMAC-SHA256 output it will produce: longer buys nothing because HMAC folds a
longer key to the block size, and shorter is the only mistake available.

**Shown once, and never again.** It is stored encrypted and there is no field on any response
for it to reappear in. A secret this platform can show twice is one this platform is storing
badly.

**Encrypted with a different key from the one that opens API key secrets.** Both are merchant
credentials, held by different services for different purposes; a compromise of one should not
be a compromise of the other, and they are rotated on different days by different people. The
cipher itself moved to `common` so there is one implementation and two keys, rather than two
copies of the algorithm.

**Bound to the endpoint's id, which the entity assigns itself.** The binding is what stops a
ciphertext being copied from one row to another and used to sign a different merchant's
traffic — and it needs the id to exist before the row is written, which a database-generated id
does not.

**Rotation is immediate, not overlapping.** Every delivery after the call is signed with the new
secret and a receiver still checking the old one will reject them. Accepting both for a while
would be a rotation that never finishes and an old secret that is never actually revoked. The
API description says so plainly and tells a merchant to deploy first or disable the endpoint.

**Event types are a closed set, checked at registration.** A typo is otherwise an endpoint that
silently receives nothing forever, which is the worst kind of bug to own: it looks exactly like
"nothing has happened yet".

**Managing is a different permission from reading.** Rotating a secret breaks every receiver
still holding the old one, and that is not something a person who only needed to look should be
able to do by clicking the wrong thing.

**The signing scheme is documented on the register operation**, including why the timestamp is
inside the signed string. A merchant who cannot check the signature will not check the
signature, and a scheme documented only in our heads is a scheme nobody verifies.

## Consequences

**Nothing is delivered yet.** This story is where a merchant can be told, not the telling.
MIZ-55 is delivery, and its hardest requirement — that a slow endpoint cannot delay anybody
else's — is the one that decides its design.

**The URL check will refuse legitimate endpoints in some deployments.** A merchant whose
endpoint genuinely is inside a private network reachable from ours cannot register it. That is
the correct default and the exception, if it is ever wanted, should be a configured allow list
rather than a hole.

**Notification-service gained an `idempotency_record` table**, because it had never had an
idempotent write before. The shape is the shared one; the code that uses it is shared, so the
table it writes to has to be.

**A closed set of event types in this service duplicates the payment service's enum.** They will
drift, and the drift shows up as a merchant unable to subscribe to something real. The honest
fix is publishing the catalogue as part of the contract, which is a story rather than a line.

## Alternatives considered

**Letting a merchant supply their own secret.** Convenient for anybody migrating from another
provider, and it means accepting whatever they type, which will sometimes be `secret`.

**Hashing the secret instead of encrypting it.** What the platform does with passwords, and it
cannot work here: HMAC is symmetric, so the side producing a signature needs the same secret
back. Encryption at least means the database alone is not enough.

**One encryption key for every secret on the platform.** One thing to configure and rotate, and
one thing to lose. Two keys for two purposes cost a line of configuration each.

**Overlapping secrets during rotation.** Kinder, and the old secret then has no moment at which
it stops working, which means it is never revoked and rotation never actually happened.

**Checking the URL only at registration.** Simpler, and it is a check an attacker waits out by
changing a DNS record afterwards.

**A blocklist of known-bad hosts rather than an allowlist of address ranges.** Fails open: every
address range somebody forgets is a hole, and the metadata endpoint is only the most famous one.
