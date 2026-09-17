package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.RefreshOutcome
import dev.kauzes.mizan.merchant.domain.Session
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The review endpoints over real HTTP, against a server answering the way payment-service does.
 *
 * The paths matter more than usual here: this app and the console are supposed to be ruling on one queue,
 * and a path that is nearly right would give the phone a queue of its own that nobody else can see.
 */
class HttpReviewsApiTest {

    private val server = MockWebServer()
    private lateinit var api: HttpReviewsApi

    private val merchant = "11111111-1111-1111-1111-111111111111"

    private class Store(var held: Session?) : SessionStore {
        override fun load() = held
        override fun save(session: Session) { held = session }
        override fun clear() { held = null }
    }

    private object NoTokens : TokenService {
        override suspend fun signIn(email: String, password: String) = SignInOutcome.Refused
        override suspend fun refresh(refreshToken: String) = RefreshOutcome.Rejected
        override suspend fun signOut(refreshToken: String) = Unit
    }

    private fun heldPayment(id: String) = """
        {"id":"$id","status":"HELD_FOR_REVIEW","amount":100000,"currency":"TRY",
         "riskVerdict":"REVIEW","riskReasons":"the amount is exactly 1000 of the major unit"}
    """.trimIndent()

    @Before
    fun start() {
        server.start()
        val signedIn = Session(
            accessToken = "access",
            refreshToken = "refresh",
            accessExpiresAt = System.currentTimeMillis() + 900_000,
            refreshExpiresAt = System.currentTimeMillis() + 2_592_000_000,
            merchantId = merchant,
            userId = "user-1",
        )
        val sessions = SessionManager(NoTokens, Store(signedIn))
        api = HttpReviewsApi(server.url("/").toString().trimEnd('/'), OkHttpClient(), sessions)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun `the queue is read from the same path the console reads`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "[${heldPayment("a")}]"))

        val answer = api.waiting()

        val asked = server.takeRequest()
        assertEquals("GET", asked.method)
        assertEquals("/api/v1/merchants/$merchant/reviews", asked.url.encodedPath)
        assertEquals("Bearer access", asked.headers["Authorization"])
        val queue = answer as ApiResult.Ok<List<RemotePayment>>
        assertEquals("the amount is exactly 1000 of the major unit", queue.value.single().riskReasons)
    }

    @Test
    fun `approving posts the reason to release, trimmed`() = runTest {
        server.enqueue(MockResponse(code = 200, body = heldPayment("a").replace("HELD_FOR_REVIEW", "AUTHORIZED")))

        val answer = api.release("a", "  a known customer  ")

        val asked = server.takeRequest()
        assertEquals("POST", asked.method)
        assertEquals("/api/v1/merchants/$merchant/reviews/a/release", asked.url.encodedPath)
        assertEquals("""{"why":"a known customer"}""", asked.body?.utf8())
        assertEquals("AUTHORIZED", (answer as ApiResult.Ok<RemotePayment>).value.status)
    }

    @Test
    fun `declining posts to refuse`() = runTest {
        server.enqueue(MockResponse(code = 200, body = heldPayment("a").replace("HELD_FOR_REVIEW", "DECLINED")))

        api.refuse("a", "card reported stolen")

        val asked = server.takeRequest()
        assertEquals("/api/v1/merchants/$merchant/reviews/a/refuse", asked.url.encodedPath)
        assertEquals("""{"why":"card reported stolen"}""", asked.body?.utf8())
    }

    @Test
    fun `a payment somebody else ruled on comes back as the platform's own sentence`() = runTest {
        server.enqueue(
            MockResponse(
                code = 422,
                body = """{"code":"UNPROCESSABLE","detail":"This payment was already ruled on."}""",
            ),
        )

        val answer = api.release("a", "a known customer")

        val refused = answer as ApiResult.Refused
        assertEquals(422, refused.status)
        assertEquals("This payment was already ruled on.", refused.detail)
    }

    @Test
    fun `an account that may not rule is refused, and that is not a lost session`() = runTest {
        server.enqueue(
            MockResponse(code = 403, body = """{"code":"FORBIDDEN","detail":"This caller may not rule on held payments."}"""),
        )

        val answer = api.waiting()

        assertEquals(403, (answer as ApiResult.Refused).status)
    }
}
