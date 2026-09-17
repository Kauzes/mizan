package dev.kauzes.mizan.merchant.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue the phone rules on: what it shows, and what it does when the platform says no. */
class ReviewQueueTest {

    private class Reviews(var answer: ApiResult<List<RemotePayment>>) : ReviewsApi {
        val ruled = mutableListOf<Triple<String, String, String>>()
        var ruling: ApiResult<RemotePayment>? = null

        override suspend fun waiting() = answer

        override suspend fun release(paymentId: String, why: String): ApiResult<RemotePayment> {
            ruled += Triple(paymentId, "release", why)
            return ruling ?: ApiResult.Ok(RemotePayment(paymentId, "AUTHORIZED", null, "0000"))
        }

        override suspend fun refuse(paymentId: String, why: String): ApiResult<RemotePayment> {
            ruled += Triple(paymentId, "refuse", why)
            return ruling ?: ApiResult.Ok(RemotePayment(paymentId, "DECLINED", null, "0000"))
        }
    }

    private fun held(id: String) =
        RemotePayment(id, "HELD_FOR_REVIEW", null, "0000", amount = 100_000, currency = "TRY", riskReasons = "the amount is exactly 1000 of the major unit")

    @Test
    fun `the queue is what the platform says is waiting`() = runTest {
        val api = Reviews(ApiResult.Ok(listOf(held("a"), held("b"))))

        val state = ReviewQueue(api).next(QueueState())

        assertEquals(listOf("a", "b"), state.waiting.map { it.id })
        assertFalse(state.loading)
        assertFalse(state.notAllowed)
    }

    @Test
    fun `an account that may not rule is told so, rather than shown an error`() = runTest {
        val api = Reviews(ApiResult.Refused(403, "FORBIDDEN", "This caller may not rule on held payments."))

        val state = ReviewQueue(api).next(QueueState())

        assertTrue(state.notAllowed)
        assertEquals(null, state.problem)
    }

    @Test
    fun `a read that fails keeps the queue that is on screen`() = runTest {
        val api = Reviews(ApiResult.Ok(listOf(held("a"))))
        val queue = ReviewQueue(api)
        val shown = queue.next(QueueState())

        api.answer = ApiResult.Unavailable(null, null, "no route to host")
        val afterFailure = queue.next(shown)

        assertEquals(listOf("a"), afterFailure.waiting.map { it.id })
        assertTrue(afterFailure.problem!!.contains("Not up to date"))
    }
}
