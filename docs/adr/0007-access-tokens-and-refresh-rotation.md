# ADR 0007: Access tokens are signed asymmetrically, and refresh tokens rotate

- Status: accepted
- Date: 2026-08-27
- Jira: MIZ-29

## Context

Signing in has to produce something the rest of the platform can check on its own. A token
that needs a call back to identity on every request makes identity a single point of failure
for every payment, and puts a network hop in front of work that should be arithmetic.

The other half of the problem is what happens when a token is stolen. A credential that lives
for days and cannot be withdrawn is a bad trade, and one that lives for minutes and forces a
password every time is a worse one.

## Decision

**Two tokens, with different jobs.** A short lived access token is verified by signature,
issuer and expiry alone, with no lookup. A longer lived refresh token is stored, revocable,
and good for exactly one use. Both lifetimes are configuration: fifteen minutes and thirty
days locally.

**The access token carries the user, the merchant it acts for and its roles.** That is what
lets a service authorise a request without asking identity anything. The cost is that nothing
can withdraw an access token before it expires, which makes its lifetime the window in which a
role change has not taken effect yet — the reason it is minutes rather than hours.

**Signed with RSA, not a shared secret.** Identity holds the private key and publishes the
public half at `/.well-known/jwks.json`. A shared secret would have been less work, and would
have meant that whoever can verify a token can also mint one. The component that most needs to
verify is the gateway, which is the component facing the internet, so it gets the half that
cannot forge.

**Only the private key is configured.** The public half is derived from it, because an RSA
private key already contains the modulus and the public exponent, and a second property is a
second thing that can be set to the wrong value. The key id is the key's own thumbprint, so it
is stable across restarts and a verifier can tell two keys apart during a rotation.

**With nothing configured, a key is generated at startup**, and the service says so at warn
level. Tokens then do not survive a restart and a second instance cannot verify the first's,
which is exactly what a local default should feel like. Committing a development key would
have made the local case smoother and put a private key in the repository.

**Refresh tokens rotate, and replay revokes the family.** Every token descended from one sign
in shares a family id. Using a refresh token spends it and issues a new pair. Presenting a
spent one means the same token reached the endpoint twice — either a client replaying its own
or somebody using a stolen copy, indistinguishable from here — so the whole family is revoked
and everyone signs in again. Losing a session beats keeping one somebody else is also holding.

**The refresh token is opaque, not a JWT.** It is 256 random bits, stored as a SHA-256 digest.
It carries no claims because nothing reads it except the endpoint that looks it up, and a
digest means a copy of the table is not a set of working credentials. bcrypt is not used here:
it exists to make guessing a low entropy secret expensive, and this secret has no low entropy
to protect.

**Every refusal is the same refusal.** A wrong password, an address with no account, an
expired token and a tampered one return the same code and the same message. Sign in also does
the hash comparison when the address is unknown, against a decoy, so an unregistered address
does not answer measurably faster than a registered one with a wrong password.

## Consequences

The revocation of a family has to be committed in its own transaction. A replay is discovered
inside the refresh transaction, and the way a caller is refused is by throwing, which rolls
that transaction back — a revocation written there would be undone and the stolen token would
go on working. The test that replays a token and then tries the family's other token is what
holds that in place.

A client that loses a response mid-refresh has spent a token it never received the replacement
for. Retrying looks exactly like a replay and takes the session down. That is the accepted
cost of detecting theft at all; the mitigation is a client that treats a refresh as
non-idempotent and signs in again rather than retrying.

`refresh_token` grows by one row per refresh and nothing prunes it. At a fifteen minute access
token that is about a hundred rows per user per day. Expired rows are dead weight rather than a
risk, and a cleanup job can come with the operational work in MIZ-11.

Identity being down does not stop existing tokens from working, but it does stop anyone signing
in or refreshing. That is the intended shape: the failure is bounded by the access token
lifetime rather than immediate.

Throttling repeated sign in attempts is deliberately absent, and belongs with gateway rate
limiting in MIZ-13. Nothing here makes it harder to add: attempts are refused in one place.

## Alternatives considered

**HMAC with a shared secret.** Simpler, and the gateway would need the same secret to verify —
making a gateway compromise a token minting compromise. Not worth the setup it saves.

**Opaque access tokens with introspection.** Revocable immediately, which is genuinely better
for a dismissal, and it puts identity in the path of every request. That is the trade this
whole story exists to avoid.

**Refresh tokens that do not rotate.** One long lived token, no family bookkeeping, and no way
to notice it was stolen. Rotation is what turns a theft into something the platform can detect
rather than something it finds out about later.

**Storing refresh tokens in Redis.** Fits their lifetime and expires them for free. It also
puts a credential in a store the platform does not otherwise treat as durable, and a restart
that empties it signs everybody out. Postgres is already there and already backed up.
