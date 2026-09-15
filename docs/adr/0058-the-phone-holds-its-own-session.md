# ADR 0058: The phone holds its own session, encrypted, and renews it once

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-98

## Context

The merchant app has to sign in against identity and stay signed in across launches, safely. Three
facts about the platform shape how:

- **Access tokens live fifteen minutes; refresh tokens thirty days** (identity's `TokenProperties`).
  Anything the merchant does after a quarter of an hour needs a renewal.
- **Refresh tokens are single use, and replaying one is treated as theft.** Presenting a spent refresh
  token revokes every token from that sign in (`RefreshTokenFamilies`). The console already learned that
  two panels refreshing at once looks exactly like a stolen token being replayed (ADR 0035).
- **A browser cannot keep a secret; a phone can.** The console keeps its refresh token in a HttpOnly
  cookie for that reason. A phone has the Android Keystore, and identity accepts the refresh token in the
  request body as well as in the cookie.

## Decision

**The phone keeps its session itself, encrypted with a key held in the Android Keystore. Every access
token the app uses comes from one session manager, which renews before expiry, renews once however many
callers ask, and ends the session only when the platform says so.**

- **Tokens in the body, not a cookie.** Sign in, refresh and sign out all send and receive the refresh
  token in JSON. There is no cookie jar in the app.
- **Encrypted at rest, by hand.** AES-256-GCM with a key generated inside the Keystore, a fresh IV per
  write, ciphertext in ordinary preferences. Jetpack's `EncryptedSharedPreferences` is deprecated, and what
  it did is a few dozen lines. A stored session that cannot be decrypted (app data cleared, Keystore reset)
  is no session: it is cleared and the merchant signs in again, never an error loop.
- **Renewed before it expires.** A token with less than a minute left is renewed before it is handed out,
  so no request leaves with a token that expires on the way.
- **Renewed once.** Renewal happens under a lock. A caller that waited for it uses the session the first
  caller got instead of spending the old refresh token a second time, which would revoke the session.
- **Only the platform ends a session.** A refresh identity rejects (401 or 400) signs the merchant out and
  forgets the tokens. A refresh that cannot reach the platform, or meets a 5xx, keeps the session and says
  the platform could not be reached. Walking into a lift is not signing out.
- **Expiry on the phone's clock.** Lifetimes are counted from when the tokens arrived (`expiresIn`), not
  read from timestamps inside the token, so a phone whose clock is minutes wrong still renews on time.
- **Which merchant, from the token.** The merchant and user ids are read from the access token's claims,
  not verified: the gateway verifies every request, and the phone only needs the id for request paths.
- **OkHttp and kotlinx.serialization** for HTTP, chosen here as the first calls that need them. **OkHttp is
  pinned at 5.4.0:** 5.5.0's Android artifact requires compileSdk 37, and this app compiles against 36,
  the highest AGP 9.0.1 supports. Read from each release's AAR metadata rather than guessed.
- **Signing out forgets the tokens first**, then tells the platform if it can. A merchant who signs out
  with no signal is signed out.

## Evidence

**Unit tests, 20.** `SessionManagerTest` (9) includes ten callers asking at once while the token needs
renewing, which spend the refresh token once; a rejected refresh signing out and forgetting the tokens; an
unreachable refresh keeping the session; a session whose refresh token expired while the app was closed
being no session; and signing out forgetting the tokens when the platform cannot be told.
`HttpTokenServiceTest` (7) runs over real HTTP with MockWebServer: request bodies, a 401 read as a
refusal, a 503 not ending the session, an unreachable host, and the merchant read from the access token.

**On an Android 16 emulator, 11.** `KeystoreSessionStoreTest` (4) against the real Keystore: what is saved
loads back; **no token, email or merchant id is readable in the preferences file**; tampered ciphertext is
no session, and is cleared. The sign in (3) and welcome (4) Compose tests.

**Driven on the emulator, against the local Compose stack**, with merchants registered through the gateway:

| | |
|---|---|
| Signed in | the home screen showed the merchant's own business |
| App killed and relaunched | straight back to that business, without signing in |
| The device's `shared_prefs/session.xml` | only `iv` and `ciphertext`: no token, email or merchant id |
| Signed out | back on sign in, nothing stored, still signed out after relaunching |

**Renewal, against the real identity service.** identity-service was run with 90 second access tokens,
so a token had under a minute left 40 seconds after signing in. Reload was then tapped, and the business
still loaded. identity's own records for that merchant:

| Refresh token | Issued | Spent | Revoked |
|---|---|---|---|
| From signing in | 20:53:53 | 20:54:44 | no |
| From the renewal | 20:54:44 | | no |

One renewal, when Reload was tapped: the first token spent once, a new one issued in the same second and
the same family, and nothing revoked, so identity saw no replay. identity-service was put back on its
fifteen minute default afterwards.

## Consequences

- **The session is only as safe as the device.** A rooted phone, or one where somebody has the unlocked
  device, can use the session while it is valid. The Keystore stops the file being copied and used
  elsewhere; it does not stop the phone being used.
- **A session ended from the console is noticed on the next request**, not immediately: the phone learns
  its refresh token is revoked when it next renews, or its access token is refused (which signs it out).
  There is no push to end a session sooner.
- **No biometric or PIN gate** on opening the app. A reasonable next step for a merchant app, and a
  separate decision.

## Alternatives

- **`EncryptedSharedPreferences`.** Deprecated, with the underlying Tink dependency the reason to move off it.
- **DataStore with a Tink-encrypted serializer.** Heavier for one small record.
- **A cookie jar, like the console.** Works, but gives up the one advantage a phone has over a browser,
  and makes the refresh token something the HTTP layer holds rather than something the app decides about.
- **Retrofit.** Reasonable; for three token calls and one read, a thin OkHttp client is less to carry.
