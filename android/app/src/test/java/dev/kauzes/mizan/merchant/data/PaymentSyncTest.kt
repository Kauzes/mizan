package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payments taken with no signal, sent when it comes back.
 *
 * The platform here can be unplugged: while it is offline every request answers "could not be reached",
 * which is what an unreachable platform looks like from the app. It honours idempotency keys the way the
 * real one does, so a payment sent twice would be visible as two charges rather than assumed away.
 */
class PaymentSyncTest {

    private class MemoryStore : AttemptStore {
        val held = mutableMapOf<String, PaymentAttempt>()
        val flow = MutableStateFlow<List<PaymentAttempt>>(emptyList())
        override suspend fun save(attempt: PaymentAttempt) {
            held[attempt.id] = attempt
            flow.value = held.values.toList()
        }
        override suspend fun find(id: String) = held[id]
        override suspend fun unfinished() = held.values.filter { it.step != AttemptStep.FINISHED }
        override fun recent(): Flow<List<PaymentAttempt>> = flow
    }

    /** The vault, in memory, with the expiry it really has. */
    private class MemoryVault : CardVault {
        val kept = mutableMapOf<String, Pair<String, Long>>()
        override suspend fun keep(attemptId: String, card: String, until: Long) {
            kept[attemptId] = card to until
        }
        override suspend fun read(attemptId: String) = kept[attemptId]?.first
        override suspend fun forget(attemptId: String) {
            kept.remove(attemptId)
        }
        override suspend fun forgetExpired(now: Long) {
            kept.entries.removeAll { it.value.second <= now }
        }
    }

    private class Platform : PaymentsApi {
        data class Payment(val id: String, var status: String, var cardLastFour: String? = null)

        val payments = mutableMapOf<String, Payment>()
        var charges = 0
        var offline = false
        var refuseCreate: ApiResult.Refused? = null
        var signedOut = false

        /** The references the platform was asked to create, in the order it was asked. */
        val order = mutableListOf<String>()

        private val answered = mutableMapOf<String, Pair<String, ApiResult<RemotePayment>>>()
        private var next = 0

        private fun remote(p: Payment) = RemotePayment(p.id, p.status, null, p.cardLastFour)

        private fun keyed(
            key: String,
            fingerprint: String,
            work: () -> ApiResult<RemotePayment>,
        ): ApiResult<RemotePayment> {
            if (signedOut) return ApiResult.SignedOut
            if (offline) return ApiResult.Unavailable(null, null, "no route to host")
            answered[key]?.let { (seen, answer) ->
                return if (seen == fingerprint) {
                    answer
                } else {
                    ApiResult.Refused(409, "IDEMPOTENCY_KEY_REUSED", "used for a different request")
                }
            }
            val answer = work()
            if (answer is ApiResult.Ok) answered[key] = fingerprint to answer
            return answer
        }

        override suspend fun create(
            key: String,
            amount: Long,
            currency: String,
            reference: String,
            description: String?,
        ) = keyed(key, "create:$amount:$reference") {
            refuseCreate?.let { return@keyed it }
            order += reference
            val payment = Payment("pay-${++next}", "CREATED")
            payments[payment.id] = payment
            ApiResult.Ok(remote(payment))
        }

        override suspend fun authorize(paymentId: String, key: String, card: String) =
            keyed(key, "authorize:$paymentId:$card") {
                val payment = payments.getValue(paymentId)
                if (payment.status != "CREATED") {
                    return@keyed ApiResult.Refused(422, "UNPROCESSABLE", "A payment that is ${payment.status} cannot be authorized.")
                }
                payment.cardLastFour = card.takeLast(4)
                payment.status = "AUTHORIZED"
                ApiResult.Ok(remote(payment))
            }

        override suspend fun capture(paymentId: String, key: String) =
            keyed(key, "capture:$paymentId") {
                val payment = payments.getValue(paymentId)
                if (payment.status != "AUTHORIZED") {
                    return@keyed ApiResult.Refused(422, "UNPROCESSABLE", "A payment that is ${payment.status} cannot be captured.")
                }
                payment.status = "CAPTURED"
                charges++
                ApiResult.Ok(remote(payment))
            }

        override suspend fun list(statuses: List<String>, size: Int) =
            ApiResult.Ok(payments.values.map(::remote).filter { statuses.isEmpty() || it.status in statuses })

        override suspend fun find(paymentId: String) = when {
            signedOut -> ApiResult.SignedOut
            offline -> ApiResult.Unavailable(null, null, "no route to host")
            else -> ApiResult.Ok(remote(payments.getValue(paymentId)))
        }
    }

    /** One phone: a platform, a store that survives a relaunch, a vault, and a taker over them. */
    private class World {
        val platform = Platform()
        val store = MemoryStore()
        val vault = MemoryVault()
        var clock = 1_000L
        private var ids = 0

        val taker = taker(platform)

        fun taker(api: PaymentsApi) = PaymentTaker(api, store, vault, now = { clock++ }, newId = { "id-${++ids}" })

        fun sync(with: PaymentTaker = taker, now: () -> Long = { clock++ }) = PaymentSync(with, vault, now)
    }

    private val card = "4000000000000000"

    @Test
    fun `a payment taken with no signal keeps its card and is sent when signal returns`() = runTest {
        val world = World()
        world.platform.offline = true
        val attempt = world.taker.start(12550, "TRY", null)

        world.taker.proceed(attempt.id, card)
        assertEquals("the card is kept, for this payment only", setOf(attempt.id), world.vault.kept.keys)
        assertEquals("nothing reached the platform", 0, world.platform.charges)

        world.platform.offline = false
        val report = world.sync().sync()

        assertEquals(1, report.finished)
        assertTrue(report.conflicts.isEmpty())
        assertEquals("charged once", 1, world.platform.charges)
        assertTrue("the card is forgotten once the authorization is answered", world.vault.kept.isEmpty())
    }

    @Test
    fun `a queue is sent oldest first, and each payment is charged once`() = runTest {
        val world = World()
        world.platform.offline = true
        val queued = listOf(
            world.taker.start(100, "TRY", "first"),
            world.taker.start(200, "TRY", "second"),
            world.taker.start(300, "TRY", "third"),
        )
        queued.forEach { world.taker.proceed(it.id, card) }

        world.platform.offline = false
        val report = world.sync().sync()

        assertEquals(3, report.finished)
        assertEquals(queued.map { it.reference }, world.platform.order)
        assertEquals(3, world.platform.charges)
        assertTrue(world.taker.unfinished().isEmpty())
    }

    @Test
    fun `a sync interrupted halfway finishes the rest next time, charging each once`() = runTest {
        val world = World()
        world.platform.offline = true
        val first = world.taker.start(100, "TRY", "first")
        val second = world.taker.start(200, "TRY", "second")
        listOf(first, second).forEach { world.taker.proceed(it.id, card) }

        // Signal comes back, then goes again as the second payment is being created.
        world.platform.offline = false
        val flaky = object : PaymentsApi by world.platform {
            var creates = 0
            override suspend fun create(
                key: String,
                amount: Long,
                currency: String,
                reference: String,
                description: String?,
            ): ApiResult<RemotePayment> {
                creates++
                if (creates == 2) world.platform.offline = true
                return world.platform.create(key, amount, currency, reference, description)
            }
        }

        val halfway = world.sync(with = world.taker(flaky)).sync()
        assertEquals("only the first went through", 1, halfway.finished)
        assertEquals(1, halfway.stillWaiting)
        assertEquals("the second still has its card", setOf(second.id), world.vault.kept.keys)

        world.platform.offline = false
        val rest = world.sync().sync()

        assertEquals(1, rest.finished)
        assertEquals("each payment charged exactly once", 2, world.platform.charges)
        assertEquals("two payments on the platform, not three", 2, world.platform.payments.size)
    }

    @Test
    fun `a reference the platform has already seen is handed to the merchant, not resolved`() = runTest {
        val world = World()
        world.platform.offline = true
        val attempt = world.taker.start(12550, "TRY", null)
        world.taker.proceed(attempt.id, card)

        world.platform.offline = false
        world.platform.refuseCreate =
            ApiResult.Refused(409, "CONFLICT", "A payment with reference ${attempt.reference} already exists.")
        val report = world.sync().sync()

        assertEquals(1, report.conflicts.size)
        assertTrue(report.conflicts.single().because.contains("already exists"))
        assertEquals(AttemptResult.REFUSED, report.conflicts.single().attempt.result)
        assertEquals(0, world.platform.charges)
        assertTrue("a refused payment does not keep a card", world.vault.kept.isEmpty())
    }

    @Test
    fun `a session that ended while offline stops the queue and says so`() = runTest {
        val world = World()
        world.platform.offline = true
        val queued = listOf(world.taker.start(100, "TRY", null), world.taker.start(200, "TRY", null))
        queued.forEach { world.taker.proceed(it.id, card) }

        world.platform.offline = false
        world.platform.signedOut = true
        val report = world.sync().sync()

        assertTrue(report.signedOut)
        assertEquals(0, report.finished)
        assertEquals("both are still there to be sent after signing in", 2, world.taker.unfinished().size)
        assertEquals(0, world.platform.charges)
    }

    @Test
    fun `a card kept too long is forgotten, and the payment asks for it again`() = runTest {
        val world = World()
        world.platform.offline = true
        val attempt = world.taker.start(12550, "TRY", null)
        world.taker.proceed(attempt.id, card)
        assertFalse(world.vault.kept.isEmpty())

        world.platform.offline = false
        val aDayLater = world.clock + 25L * 60 * 60 * 1000
        val report = world.sync(now = { aDayLater }).sync()

        assertNull("the card is gone", world.vault.read(attempt.id))
        assertEquals(1, report.conflicts.size)
        assertTrue(report.conflicts.single().because.contains("needs the card again"))
        assertEquals("nobody was charged", 0, world.platform.charges)
        assertEquals("and the payment is still there", 1, world.taker.unfinished().size)
    }

    @Test
    fun `a second sync finds nothing left to do`() = runTest {
        val world = World()
        world.platform.offline = true
        val attempt = world.taker.start(12550, "TRY", null)
        world.taker.proceed(attempt.id, card)

        world.platform.offline = false
        val sync = world.sync()
        val reports = listOf(sync.sync(), sync.sync())

        assertEquals("one payment went through, across both passes", 1, reports.sumOf { it.finished })
        assertEquals(1, world.platform.charges)
    }
}
