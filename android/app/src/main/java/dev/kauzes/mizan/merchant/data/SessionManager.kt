package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AccessOutcome
import dev.kauzes.mizan.merchant.domain.RefreshOutcome
import dev.kauzes.mizan.merchant.domain.Session
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The platform's token endpoints, behind an interface so the session logic can be tested without HTTP. */
interface TokenService {
    suspend fun signIn(email: String, password: String): SignInOutcome
    suspend fun refresh(refreshToken: String): RefreshOutcome
    suspend fun signOut(refreshToken: String)
}

/** Where a session is kept between launches. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

/**
 * The one place the app gets an access token from.
 *
 * **Renewed once, however many callers ask at the same moment.** The platform's refresh tokens are
 * single use, and presenting one twice is treated as theft: every token from that sign in is revoked
 * (identity's RefreshTokenFamilies). Two screens noticing an expired token together and each
 * refreshing would therefore sign the merchant out. So renewal happens under a lock, and a caller that
 * waited for it uses the session the first caller got rather than spending the old token again.
 *
 * **Renewed before it expires, not after.** A token about to expire is renewed when it has less than
 * [renewWithin] left, so a request is never sent with a token that expires on its way.
 *
 * **Only the platform can end a session.** A refresh the platform rejects signs the merchant out. A
 * refresh that could not reach the platform does not: a phone with no signal still holds a good session,
 * and signing somebody out because they walked into a lift would be wrong.
 */
class SessionManager(
    private val tokens: TokenService,
    private val store: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val renewWithin: Long = 60_000,
) {
    private val renewal = Mutex()
    private val mutableCurrent = MutableStateFlow(store.load()?.takeIf { it.refreshExpiresAt > now() })

    /** The session, or null when nobody is signed in. Becomes null when the platform ends it. */
    val current: StateFlow<Session?> = mutableCurrent.asStateFlow()

    init {
        // A session whose refresh token has expired while the app was closed is not a session.
        if (mutableCurrent.value == null) store.clear()
    }

    suspend fun signIn(email: String, password: String): SignInOutcome {
        val outcome = tokens.signIn(email, password)
        if (outcome is SignInOutcome.SignedIn) {
            store.save(outcome.session)
            mutableCurrent.value = outcome.session
        }
        return outcome
    }

    /** A token good for at least [renewWithin], renewing it first if it is not. */
    suspend fun accessToken(): AccessOutcome {
        val seen = mutableCurrent.value ?: return AccessOutcome.SignedOut
        if (seen.accessExpiresAt - now() > renewWithin) return AccessOutcome.Valid(seen.accessToken)

        return renewal.withLock {
            val latest = mutableCurrent.value ?: return@withLock AccessOutcome.SignedOut
            if (latest.refreshToken != seen.refreshToken && latest.accessExpiresAt - now() > renewWithin) {
                // Somebody else renewed it while this caller waited for the lock.
                return@withLock AccessOutcome.Valid(latest.accessToken)
            }
            if (latest.refreshExpiresAt <= now()) {
                end()
                return@withLock AccessOutcome.SignedOut
            }

            when (val renewed = tokens.refresh(latest.refreshToken)) {
                is RefreshOutcome.Renewed -> {
                    store.save(renewed.session)
                    mutableCurrent.value = renewed.session
                    AccessOutcome.Valid(renewed.session.accessToken)
                }
                RefreshOutcome.Rejected -> {
                    end()
                    AccessOutcome.SignedOut
                }
                is RefreshOutcome.Unreachable -> AccessOutcome.Unreachable(renewed.because)
            }
        }
    }

    /**
     * Ends the session on this phone, and tells the platform if it can.
     *
     * The phone forgets the tokens whether or not the platform heard: a merchant who signs out on a
     * phone with no signal expects to be signed out, and the refresh token expires on its own.
     */
    suspend fun signOut() {
        val session = renewal.withLock {
            val held = mutableCurrent.value
            end()
            held
        } ?: return
        runCatching { tokens.signOut(session.refreshToken) }
    }

    private fun end() {
        store.clear()
        mutableCurrent.value = null
    }
}
