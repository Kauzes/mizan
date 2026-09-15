package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import dev.kauzes.mizan.merchant.domain.ProceedOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a payment against a platform that honours idempotency keys the way the real one does.
 *
 * The fake platform remembers every key and what it answered, replays that answer for the same key and
 * body, refuses the same key with a different body, and gives a key back when its request failed. A crash
 * is modelled as the platform doing the work and the answer never arriving, followed by a new
 * PaymentTaker over the same store, which is what a relaunched app is.
 */
class PaymentTakerTest {

    private class MemoryStore : AttemptStore {
        val held = mutableMapOf<String, PaymentAttempt>()
        val flow = MutableStateFlow<List<PaymentAttempt>>(emptyList())
        override suspend fun save(attempt: PaymentAttempt) { held[attempt.id] = attempt; flow.value = held.values.toList() }
        override suspend fun find(id: String) = held[id]
        override suspend fun unfinished() = held.values.filter { it.step != AttemptStep.FINISHED }
        override fun recent(): Flow<List<PaymentAttempt>> = flow
    }

    /** What the platform holds, and a switch for each way a request can go wrong. */
    private class FakePlatform(private val store: MemoryStore? = null) : PaymentsApi {
        data class Payment(val id: String, var status: String, var cardLastFour: String? = null, var declineReason: String? = null)

        val payments = mutableMapOf<String, Payment>()
        val charges = AtomicInteger()
        private val answered = mutableMapOf<String, Pair<String, ApiResult<RemotePayment>>>()
        private var next = 0

        /** The card the acquirer approves; anything ending 0002 is declined. */
        var authorizeResult: (String) -> String = { card -> if (card.endsWith("0002")) "DECLINED" else "AUTHORIZED" }
        var riskHolds = false

        /** Do the work, then lose the answer: the platform acted and the phone never heard. */
        var loseNextAnswer = false
        var nextUnavailable: ApiResult.Unavailable? = null
        var timeoutOnAuthorize = false

        /** Keys the platform saw, so a test can check what was sent before a crash. */
        val keysSeen = mutableListOf<String>()
        var storeHadAttemptWhenFirstCalled: Boolean? = null

        private fun remote(p: Payment) = RemotePayment(p.id, p.status, p.declineReason, p.cardLastFour)

        private fun keyed(key: String, fingerprint: String, work: () -> ApiResult<RemotePayment>): ApiResult<RemotePayment> {
            if (storeHadAttemptWhenFirstCalled == null) storeHadAttemptWhenFirstCalled = store?.held?.isNotEmpty()
            keysSeen += key
            nextUnavailable?.let { nextUnavailable = null; return it }
            answered[key]?.let { (seen, answer) ->
                return if (seen == fingerprint) answer else ApiResult.Refused(409, "IDEMPOTENCY_KEY_REUSED", "used for a different request")
            }
            val answer = work()
            if (answer is ApiResult.Ok) answered[key] = fingerprint to answer
            if (loseNextAnswer) {
                loseNextAnswer = false
                return ApiResult.Unavailable(null, null, "connection reset")
            }
            return answer
        }

        override suspend fun create(key: String, amount: Long, currency: String, reference: String, description: String?) =
            keyed(key, "create:$amount:$currency:$reference") {
                val p = Payment("pay-${++next}", "CREATED")
                payments[p.id] = p
                ApiResult.Ok(remote(p))
            }

        override suspend fun authorize(paymentId: String, key: String, card: String) =
            keyed(key, "authorize:$paymentId:$card") {
                val p = payments.getValue(paymentId)
                if (timeoutOnAuthorize) {
                    timeoutOnAuthorize = false
                    p.status = "AUTHORIZED"
                    p.cardLastFour = card.takeLast(4)
                    return@keyed ApiResult.Unavailable(504, "UPSTREAM_TIMEOUT", "the acquirer did not answer in time")
                }
                if (p.status != "CREATED") return@keyed ApiResult.Refused(422, "UNPROCESSABLE", "A payment that is ${p.status} cannot be authorized.")
                p.cardLastFour = card.takeLast(4)
                p.status = if (riskHolds) "HELD_FOR_REVIEW" else authorizeResult(card)
                if (p.status == "DECLINED") p.declineReason = "insufficient_funds"
                ApiResult.Ok(remote(p))
            }

        override suspend fun capture(paymentId: String, key: String) =
            keyed(key, "capture:$paymentId") {
                val p = payments.getValue(paymentId)
                if (p.status != "AUTHORIZED") return@keyed ApiResult.Refused(422, "UNPROCESSABLE", "A payment that is ${p.status} cannot be captured.")
                p.status = "CAPTURED"
                charges.incrementAndGet()
                ApiResult.Ok(remote(p))
            }

        override suspend fun find(paymentId: String): ApiResult<RemotePayment> =
            nextUnavailable?.let { nextUnavailable = null; it } ?: ApiResult.Ok(remote(payments.getValue(paymentId)))
    }

    private var clock = 1_000L
    private var ids = 0
    private fun taker(api: PaymentsApi, store: AttemptStore) =
        PaymentTaker(api, store, now = { clock++ }, newId = { "id-${++ids}" })

    private val card = "4000000000000000"

    @Test
    fun `a payment is written down with all its keys before anything is sent`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store)
        val taker = taker(platform, store)

        val attempt = taker.start(12550, "TRY", "two coffees")
        assertTrue("nothing has been sent yet", platform.keysSeen.isEmpty())
        assertEquals(setOf(attempt.createKey, attempt.authorizeKey, attempt.captureKey).size, 3)

        taker.proceed(attempt.id, card)
        assertEquals("the store held the attempt when the first request left", true, platform.storeHadAttemptWhenFirstCalled)
    }

    @Test
    fun `an ordinary payment is created, authorized and captured once`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store)
        val taker = taker(platform, store)

        val outcome = taker.proceed(taker.start(12550, "TRY", null).id, card)

        assertTrue(outcome is ProceedOutcome.Finished)
        assertEquals(AttemptResult.CAPTURED, outcome.attempt.result)
        assertEquals("0000", outcome.attempt.cardLastFour)
        assertEquals(1, platform.charges.get())
    }

    @Test
    fun `the app dying after every step, and reopening, still charges once`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store)
        val id = taker(platform, store).start(12550, "TRY", null).id

        // Create: the platform records it, the answer is lost, the app is killed.
        platform.loseNextAnswer = true
        assertTrue(taker(platform, store).proceed(id, card) is ProceedOutcome.Waiting)

        // Reopened: create replays; authorize happens and its answer is lost too.
        platform.loseNextAnswer = false
        val afterCreate = taker(platform, store)
        platform.loseNextAnswer = false
        afterCreate.proceed(id).also { assertTrue("the authorization needs the card again", it is ProceedOutcome.NeedsCard) }
        platform.loseNextAnswer = true
        assertTrue(afterCreate.proceed(id, card) is ProceedOutcome.Waiting)

        // Reopened again: the same card replays the authorization; the capture's answer is lost.
        val afterAuthorize = taker(platform, store)
        // The authorize replay succeeds and capture runs within the same proceed; lose the capture's answer.
        val lostCapture = object : PaymentsApi by platform {
            var captures = 0
            override suspend fun capture(paymentId: String, key: String): ApiResult<RemotePayment> {
                captures++
                val real = platform.capture(paymentId, key)
                return if (captures == 1) ApiResult.Unavailable(null, null, "connection reset") else real
            }
        }
        assertTrue(PaymentTaker(lostCapture, store, now = { clock++ }).proceed(id, card) is ProceedOutcome.Waiting)
        assertEquals("the capture happened on the platform", 1, platform.charges.get())

        // Reopened a last time: the capture replays with its key.
        val finished = afterAuthorize.proceed(id)
        assertTrue(finished is ProceedOutcome.Finished)
        assertEquals(AttemptResult.CAPTURED, finished.attempt.result)
        assertEquals("one payment on the platform", 1, platform.payments.size)
        assertEquals("charged once", 1, platform.charges.get())
    }

    @Test
    fun `an authorization the bank did not answer in time is read back, not sent again`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store).apply { timeoutOnAuthorize = true }
        val taker = taker(platform, store)
        val id = taker.start(12550, "TRY", null).id

        val first = taker.proceed(id, card)
        assertTrue(first is ProceedOutcome.Waiting)
        assertEquals(AttemptStep.CONFIRMING_AUTHORIZATION, first.attempt.step)

        val second = taker.proceed(id)
        assertTrue("no card was needed: the platform said it was authorized", second is ProceedOutcome.Finished)
        assertEquals(AttemptResult.CAPTURED, second.attempt.result)
        assertEquals(1, platform.charges.get())
    }

    @Test
    fun `a held payment ends without a capture, and nobody is charged`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store).apply { riskHolds = true }
        val taker = taker(platform, store)

        val outcome = taker.proceed(taker.start(12550, "TRY", null).id, card)

        assertEquals(AttemptResult.HELD_FOR_REVIEW, outcome.attempt.result)
        assertEquals(0, platform.charges.get())
    }

    @Test
    fun `a declined card ends the payment with the acquirer's reason`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store)
        val taker = taker(platform, store)

        val outcome = taker.proceed(taker.start(12550, "TRY", null).id, "4000000000000002")

        assertEquals(AttemptResult.DECLINED, outcome.attempt.result)
        assertEquals("insufficient_funds", outcome.attempt.detail)
        assertEquals(0, platform.charges.get())
    }

    @Test
    fun `a different card on a retry is refused, not charged as a new payment`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store)
        val id = taker(platform, store).start(12550, "TRY", null).id
        taker(platform, store).proceed(id) // created, needs the card

        // The first card's authorization happens and its answer is lost.
        platform.loseNextAnswer = true
        taker(platform, store).proceed(id, card)

        val retried = taker(platform, store).proceed(id, "5500000000000004")
        assertTrue(retried is ProceedOutcome.CardDiffers)
        assertEquals(0, platform.charges.get())
    }

    @Test
    fun `a platform that could not be reached is waited on, and the same key is sent next time`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store).apply { nextUnavailable = ApiResult.Unavailable(503, "UPSTREAM_UNAVAILABLE", "the acquirer could not be reached") }
        val taker = taker(platform, store)
        val attempt = taker.start(12550, "TRY", null)

        assertTrue(taker.proceed(attempt.id, card) is ProceedOutcome.Waiting)
        val finished = taker.proceed(attempt.id, card)

        assertEquals(AttemptResult.CAPTURED, finished.attempt.result)
        assertEquals("both tries of creating used one key", 2, platform.keysSeen.count { it == attempt.createKey })
        assertEquals(1, platform.charges.get())
    }

    @Test
    fun `unfinished payments are found again after a relaunch`() = runTest {
        val store = MemoryStore()
        val platform = FakePlatform(store)
        val id = taker(platform, store).start(12550, "TRY", null).id
        taker(platform, store).proceed(id)

        val found = taker(platform, store).unfinished()
        assertEquals(listOf(id), found.map { it.id })
        assertNotNull(found.single().paymentId)
    }
}
