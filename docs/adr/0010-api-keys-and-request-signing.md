# ADR 0010: Merchant servers sign requests, and their secrets are encrypted rather than hashed

- Status: accepted
- Date: 2026-08-30
- Jira: MIZ-32
- Revisited: 2026-09-01, after review. HMAC confirmed; ciphertext bound to its key row.

## Context

A merchant's server is not a person and should not carry a token issued to one. It needs a
credential it can hold for months, that a merchant can revoke without anybody signing out, and
that survives being seen: a long lived bearer secret is one packet capture away from being
somebody else's.

This is also the credential an attacker would most like to replay, because it moves money.

## Decision

**A key names itself and a signature proves the request.** `X-Mizan-Key` says which key,
`X-Mizan-Timestamp` says when, and `X-Mizan-Signature` is HMAC-SHA256 over a canonical request
under the key's secret. The secret never travels.

**The canonical request is four lines**: uppercase method, path, timestamp, and the SHA-256 of
the body. Each line earns its place. Without the method or path, a captured signature could be
aimed at a different endpoint. Without the body, it could be replayed with different numbers in
it. Without the timestamp, it could be replayed at all. A request whose timestamp is outside
the accepted window is refused whatever its signature says, and that window is configuration
because how far clocks drift is a property of the deployment.

**Signatures are compared with a constant time comparison.** A comparison that stops at the
first wrong character leaks the signature one character at a time to somebody patient enough
to measure.

**Each stored value is bound to the key it belongs to.** The key id is authenticated alongside
the ciphertext, so a value copied onto another key's row fails to open. Without that, every
ciphertext under the same encryption key is interchangeable: somebody able to write to this
table could move the encrypted secret from a key they legitimately hold onto another merchant's
row and sign that merchant's requests with a secret they already know. The binding costs
nothing and removes a database write from being enough on its own.

**The secret is stored encrypted, not hashed.** This is a deliberate departure from the story,
which asked for a hash, and from the advice that would be right for a password. HMAC is
symmetric: whoever checks a signature must hold the same secret that made it, and a hash holds
nothing that can verify anything. What encryption buys instead is that the database alone is
not enough — the key that opens these values is configuration and lives wherever the deployment
keeps secrets. AES-GCM, fresh nonce per value, so a row edited in place fails to decrypt rather
than decrypting to something else.

**Identity verifies, the gateway asks.** The gateway could have been handed the secrets and
checked signatures itself, saving a round trip. It would also mean the component facing the
internet holds every merchant's signing secret, and that a revoked key kept working until a
cache expired. Asking every time is what makes revocation immediate, which is the property the
story asked to be tested rather than assumed.

**The gateway reads the body, and replays it.** The signature covers the body, so the body has
to be read at the edge; what is forwarded is the same bytes, so the request a service acts on
is exactly the one the signature was checked against. A signed request larger than the
configured maximum is refused rather than buffered.

**Credentials stop at the edge.** The key, signature, timestamp and authorization headers are
removed before the request is forwarded. A service has no use for them, and a credential that
stops here cannot be logged by six other places.

**One role per key**, chosen when it is issued. A server integration does one job, and a key
that can do everything is a key whose theft costs everything. Managing keys needs
`API_KEY_MANAGE`, which only an owner holds.

**Rotation is one step.** Issuing the replacement and revoking the original together, because a
rotation done as two calls has a gap in the middle where the merchant either has no working key
or has two, depending which they did first. The new key records which key it replaced.

## Consequences

The encryption key is now something a deployment must set and must not lose. Losing it does not
expose anything, but every issued key stops verifying and every merchant has to rotate. With
nothing configured, one is generated for the process and the service says so loudly: keys
issued then stop working at the next restart. The local stack pins an obviously local value in
`docker-compose.yml`, whose plaintext reads
`mizan-local-development-only-key`, so that a laptop restart does not invalidate keys mid
demonstration.

Identity is now on the path of every signed request. It was deliberately kept off the path of
token authentication in ADR 0007, and this is the opposite trade, made for the opposite reason:
tokens expire on their own, keys have to be revocable the moment somebody clicks revoke. If
signed traffic ever outgrows this, the answer is to make identity fast or replicated, not to
cache the secrets at the edge.

The verification endpoint is not under `/api/` and the gateway publishes no route to it, but it
is reachable through the gateway's `/internal/**` route by anybody holding a valid access
token. What that reveals is whether a signature is valid, which sending the actual request
would reveal anyway. It is on the list for the network policy work in MIZ-12 rather than solved
here.

A merchant integrating against this has to implement the canonical form exactly. The spec now
describes it with a worked example, and the tests that exercise it build their signatures from
the published definition alone rather than from the platform's internals, which is what keeps
the description honest.

Nothing here rate limits a caller presenting a key. That is MIZ-13's, and this makes it no
harder: signed requests are refused in one place.

## Alternatives considered

**Asymmetric request signing**, where the merchant holds a private key and the platform stores
only the public half. This was reconsidered in full after the scheme was built, because it is
the option that would satisfy the story's wording exactly, and it was rejected on the merits.

What it offers: the stored value cannot sign anything, so a database dump is worthless and
there is no encryption key to lose. And non-repudiation — only the merchant could have produced
that signature, which is worth real money in a disputed instruction.

Why it was not taken: **the non-repudiation argument does not survive contact with the rest of
this platform.** Mizan holds the JWT signing key and every password hash, so it can already mint
an access token for any user and act as that merchant through the console. Asymmetric API keys
would close one door and leave that one open, buying a property the system as a whole does not
provide. Getting the property for real would mean merchants generating their own keypairs and
uploading only public halves — a private key we hand over is one we held — which makes
onboarding materially harder for something the platform cannot honour anyway.

Against that: HMAC-SHA256 is a few lines in every language with no key formats to misparse,
which is why Stripe, Shopify, Twilio and AWS all sign this way; and it is what MIZ-25 already
published as `X-Mizan-Signature`.

The conclusion holds only as long as the premise does. If Mizan ever stops being able to act as
a merchant — hardware-held signing keys, or an identity provider it does not control — the
non-repudiation argument becomes real and this decision should be reopened.

**A bearer API key, compared against a stored hash.** What Stripe does for its secret keys, and
it would have allowed hash-only storage. It also means the credential itself crosses the wire on
every request, so anything that logs a header logs a working key, and nothing binds the
credential to the request it authorises.

**Verifying at the gateway with cached secrets.** Faster, and it puts every merchant's signing
secret in the process facing the internet, with revocation delayed by whatever the cache
allows. Both are the wrong side of the trade for a payments platform.

**Signing only method, path and timestamp.** Simpler, and the gateway would not have to read
the body. It also means a captured signature can be replayed against the same endpoint with a
different amount in it, which is the attack this whole scheme exists to prevent.
