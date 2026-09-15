package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AccessOutcome
import dev.kauzes.mizan.merchant.domain.RefreshOutcome
import dev.kauzes.mizan.merchant.domain.Session
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    private var clock = 1_000_000L

    private fun session(tag: String, accessFor: Long = 900_000, refreshFor: Long = 2_592_000_000) = Session(
        accessToken = "access-$tag",
        refreshToken = "refresh-$tag",
        accessExpiresAt = clock + accessFor,
        refreshExpiresAt = clock + refreshFor,
        merchantId = "merchant-1",
        userId = "user-1",
    )

    private class MemoryStore(var held: Session? = null) : SessionStore {
        var cleared = 0
        override fun load() = held
        override fun save(session: Session) { held = session }
        override fun clear() { held = null; cleared++ }
    }

    /** The platform's token endpoints, answering as told and counting what it was asked. */
    private inner class FakeTokens : TokenService {
        var signInAnswer: SignInOutcome = SignInOutcome.Refused
        var refreshAnswer: () -> RefreshOutcome = { RefreshOutcome.Renewed(session("renewed")) }
        val refreshes = AtomicInteger()
        val spent = mutableListOf<String>()
        var holdRefresh: CompletableDeferred<Unit>? = null
        var signedOut: String? = null
        var signOutFails = false

        override suspend fun signIn(email: String, password: String) = signInAnswer
        override suspend fun refresh(refreshToken: String): RefreshOutcome {
            refreshes.incrementAndGet()
            synchronized(spent) { spent += refreshToken }
            holdRefresh?.await()
            return refreshAnswer()
        }
        override suspend fun signOut(refreshToken: String) {
            if (signOutFails) throw java.io.IOException("no signal")
            signedOut = refreshToken
        }
    }

    @Test
    fun `signing in keeps the session, and a refusal keeps nothing`() = runTest {
        val tokens = FakeTokens()
        val store = MemoryStore()
        val sessions = SessionManager(tokens, store, now = { clock })

        tokens.signInAnswer = SignInOutcome.Refused
        assertEquals(SignInOutcome.Refused, sessions.signIn("owner@kauzes.dev", "wrong"))
        assertNull(store.held)
        assertNull(sessions.current.value)

        val signedIn = session("first")
        tokens.signInAnswer = SignInOutcome.SignedIn(signedIn)
        sessions.signIn("owner@kauzes.dev", "right")
        assertEquals(signedIn, store.held)
        assertEquals(signedIn, sessions.current.value)
    }

    @Test
    fun `a token with time left is used as it is, without asking the platform`() = runTest {
        val tokens = FakeTokens()
        val sessions = SessionManager(tokens, MemoryStore(session("fresh")), now = { clock })

        assertEquals(AccessOutcome.Valid("access-fresh"), sessions.accessToken())
        assertEquals(0, tokens.refreshes.get())
    }

    @Test
    fun `a token about to expire is renewed before it is used`() = runTest {
        val tokens = FakeTokens()
        val store = MemoryStore(session("old", accessFor = 30_000))
        val sessions = SessionManager(tokens, store, now = { clock })

        assertEquals(AccessOutcome.Valid("access-renewed"), sessions.accessToken())
        assertEquals(listOf("refresh-old"), tokens.spent)
        assertEquals("refresh-renewed", store.held?.refreshToken)
    }

    @Test
    fun `many callers at once spend the refresh token once`() = runTest {
        // The case that would sign a merchant out: the platform revokes the whole session when a
        // refresh token is presented twice.
        val tokens = FakeTokens().apply { holdRefresh = CompletableDeferred() }
        val sessions = SessionManager(tokens, MemoryStore(session("old", accessFor = 0)), now = { clock })

        val callers = (1..10).map { async { sessions.accessToken() } }
        testScheduler.advanceUntilIdle()
        tokens.holdRefresh!!.complete(Unit)
        val answers = callers.awaitAll()

        assertEquals("the refresh token was spent once", 1, tokens.refreshes.get())
        assertEquals(listOf("refresh-old"), tokens.spent)
        assertTrue(answers.all { it == AccessOutcome.Valid("access-renewed") })
    }

    @Test
    fun `a refresh the platform rejects signs the merchant out and forgets the tokens`() = runTest {
        val tokens = FakeTokens().apply { refreshAnswer = { RefreshOutcome.Rejected } }
        val store = MemoryStore(session("revoked", accessFor = 0))
        val sessions = SessionManager(tokens, store, now = { clock })

        assertEquals(AccessOutcome.SignedOut, sessions.accessToken())
        assertNull(store.held)
        assertNull(sessions.current.value)
    }

    @Test
    fun `a refresh that cannot reach the platform keeps the session`() = runTest {
        // No signal is not the platform ending the session.
        val tokens = FakeTokens().apply { refreshAnswer = { RefreshOutcome.Unreachable("no signal") } }
        val held = session("held", accessFor = 0)
        val store = MemoryStore(held)
        val sessions = SessionManager(tokens, store, now = { clock })

        assertEquals(AccessOutcome.Unreachable("no signal"), sessions.accessToken())
        assertEquals(held, store.held)
        assertEquals(held, sessions.current.value)
    }

    @Test
    fun `a session whose refresh token expired while the app was closed is no session`() {
        val store = MemoryStore(session("stale", accessFor = -1, refreshFor = -1))
        val sessions = SessionManager(FakeTokens(), store, now = { clock })

        assertNull(sessions.current.value)
        assertNull(store.held)
    }

    @Test
    fun `signing out forgets the tokens even when the platform cannot be told`() = runTest {
        val tokens = FakeTokens().apply { signOutFails = true }
        val store = MemoryStore(session("mine"))
        val sessions = SessionManager(tokens, store, now = { clock })

        sessions.signOut()

        assertNull(store.held)
        assertNull(sessions.current.value)
        assertEquals(AccessOutcome.SignedOut, sessions.accessToken())
    }

    @Test
    fun `signing out tells the platform which session ended`() = runTest {
        val tokens = FakeTokens()
        val sessions = SessionManager(tokens, MemoryStore(session("mine")), now = { clock })

        sessions.signOut()

        assertEquals("refresh-mine", tokens.signedOut)
    }
}
