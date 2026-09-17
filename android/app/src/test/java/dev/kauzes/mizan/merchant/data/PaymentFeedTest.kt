package dev.kauzes.mizan.merchant.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list a merchant watches: what it shows, and what it does when a read fails. */
class PaymentFeedTest {

    private class Listing(var answer: ApiResult<List<RemotePayment>>) : PaymentsApi {
        var asked = 0
        var askedFor: List<String>? = null

        override suspend fun list(statuses: List<String>, size: Int): ApiResult<List<RemotePayment>> {
            asked++
            askedFor = statuses
            return answer
        }

        override suspend fun create(key: String, amount: Long, currency: String, reference: String, description: String?) =
            error("not used")
        override suspend fun authorize(paymentId: String, key: String, card: String) = error("not used")
        override suspend fun capture(paymentId: String, key: String) = error("not used")
        override suspend fun find(paymentId: String) = error("not used")
    }

    private fun payment(id: String, status: String = "CAPTURED") =
        RemotePayment(id, status, null, "0000", amount = 12550, currency = "TRY")

    @Test
    fun `the payments the platform lists are what the screen shows`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(payment("a"), payment("b"))))

        val state = PaymentFeed(api).next(FeedState())

        assertEquals(listOf("a", "b"), state.payments.map { it.id })
        assertFalse(state.loading)
        assertEquals("nothing is filtered out: the merchant sees every payment", emptyList<String>(), api.askedFor)
    }

    @Test
    fun `a read that fails keeps what is on screen and says it is not up to date`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(payment("a"))))
        val feed = PaymentFeed(api)
        val shown = feed.next(FeedState())

        api.answer = ApiResult.Unavailable(null, null, "no route to host")
        val afterFailure = feed.next(shown)

        assertEquals("the payments are still there", listOf("a"), afterFailure.payments.map { it.id })
        assertTrue(afterFailure.problem!!.contains("Not up to date"))
    }

    @Test
    fun `a refusal is shown with the platform's own reason`() = runTest {
        val api = Listing(ApiResult.Refused(422, "UNPROCESSABLE", "A filter that cannot be answered."))

        val state = PaymentFeed(api).next(FeedState())

        assertEquals("A filter that cannot be answered.", state.problem)
    }

    @Test
    fun `a session that ended is said once, rather than shown as a failed read`() = runTest {
        val api = Listing(ApiResult.SignedOut)

        val state = PaymentFeed(api).next(FeedState())

        assertTrue(state.signedOut)
        assertEquals(null, state.problem)
    }
}
