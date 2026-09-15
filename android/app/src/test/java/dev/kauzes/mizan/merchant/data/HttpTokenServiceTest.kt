package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.RefreshOutcome
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import java.util.Base64
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The token endpoints over real HTTP, against a server that answers the way identity does. */
class HttpTokenServiceTest {

    private val server = MockWebServer()
    private val clock = 5_000_000L

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun service(url: String = server.url("/").toString()) =
        HttpTokenService(url, OkHttpClient(), now = { clock })

    private fun jwt(sub: String, merchant: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$sub","merchant":"$merchant","roles":["ADMIN"]}""".toByteArray())
        return "$header.$payload.signature"
    }

    private fun tokenPair(access: String, refresh: String) = """
        {"accessToken":"$access","tokenType":"Bearer","expiresIn":900,
         "refreshToken":"$refresh","refreshExpiresIn":2592000}
    """.trimIndent()

    @Test
    fun `signing in sends the credentials and keeps what identity issued`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(tokenPair(jwt("user-7", "merchant-3"), "refresh-1")).build())

        val outcome = service().signIn("owner@kauzes.dev", "a-password")

        val request = server.takeRequest()
        assertEquals("/api/v1/tokens", request.url.encodedPath)
        assertEquals("POST", request.method)
        val sent = request.body!!.utf8()
        assertTrue(sent.contains("\"email\":\"owner@kauzes.dev\""))
        assertTrue(sent.contains("\"password\":\"a-password\""))

        val session = (outcome as SignInOutcome.SignedIn).session
        assertEquals("refresh-1", session.refreshToken)
        assertEquals("merchant-3", session.merchantId)
        assertEquals("user-7", session.userId)
        assertEquals("expiry counted from arrival, on this phone's clock", clock + 900_000, session.accessExpiresAt)
        assertEquals(clock + 2_592_000_000, session.refreshExpiresAt)
    }

    @Test
    fun `a 401 is a refusal, not a failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"UNAUTHORIZED"}""").build())

        assertEquals(SignInOutcome.Refused, service().signIn("owner@kauzes.dev", "wrong"))
    }

    @Test
    fun `nothing listening is unreachable`() = runTest {
        val outcome = service("http://127.0.0.1:1/").signIn("owner@kauzes.dev", "a-password")

        assertTrue(outcome is SignInOutcome.Unreachable)
    }

    @Test
    fun `refreshing spends the token in the body and returns the renewed pair`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(tokenPair(jwt("user-7", "merchant-3"), "refresh-2")).build())

        val outcome = service().refresh("refresh-1")

        val request = server.takeRequest()
        assertEquals("/api/v1/tokens/refresh", request.url.encodedPath)
        assertEquals("""{"refreshToken":"refresh-1"}""", request.body!!.utf8())
        assertEquals("refresh-2", (outcome as RefreshOutcome.Renewed).session.refreshToken)
    }

    @Test
    fun `a refresh token identity refuses ends the session, and a server error does not`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(RefreshOutcome.Rejected, service().refresh("spent"))

        server.enqueue(MockResponse.Builder().code(503).build())
        val unwell = service().refresh("good")
        assertTrue("a platform that is unwell has not ended anybody's session", unwell is RefreshOutcome.Unreachable)
    }

    @Test
    fun `the merchant and user are read from the access token`() {
        val claims = AccessTokenClaims.of(jwt("user-9", "merchant-4"))

        assertEquals("user-9", claims.userId)
        assertEquals("merchant-4", claims.merchantId)
    }

    @Test
    fun `a token with no merchant in it is not accepted as a session`() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val noMerchant = "x." + encoder.encodeToString("""{"sub":"user-1"}""".toByteArray()) + ".y"

        assertFalse(runCatching { AccessTokenClaims.of(noMerchant) }.isSuccess)
    }
}
