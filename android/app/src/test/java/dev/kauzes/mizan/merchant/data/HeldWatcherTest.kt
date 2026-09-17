package dev.kauzes.mizan.merchant.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling the merchant about a payment held for review — once, and only about held payments. */
class HeldWatcherTest {

    private class Listing(var answer: ApiResult<List<RemotePayment>>) : PaymentsApi {
        var askedFor: List<String>? = null

        override suspend fun list(statuses: List<String>, size: Int): ApiResult<List<RemotePayment>> {
            askedFor = statuses
            return answer
        }

        override suspend fun create(key: String, amount: Long, currency: String, reference: String, description: String?) =
            error("not used")
        override suspend fun authorize(paymentId: String, key: String, card: String) = error("not used")
        override suspend fun capture(paymentId: String, key: String) = error("not used")
        override suspend fun find(paymentId: String) = error("not used")
    }

    /** Preferences, in memory: the same "remembered across launches" the real one has. */
    private class Remembered : SeenHeld {
        var ids = emptySet<String>()
        override suspend fun seen() = ids
        override suspend fun remember(ids: Set<String>) { this.ids = ids }
    }

    private class Told : HeldNotifier {
        val payments = mutableListOf<RemotePayment>()
        override fun tell(payment: RemotePayment) { payments += payment }
    }

    private fun held(id: String) = RemotePayment(id, "HELD_FOR_REVIEW", null, "0000", amount = 100_000, currency = "TRY")

    @Test
    fun `a payment held for review is announced`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(held("a"))))
        val told = Told()

        val fresh = HeldWatcher(api, Remembered(), told).check()

        assertEquals(listOf("a"), fresh.map { it.id })
        assertEquals(listOf("a"), told.payments.map { it.id })
        assertEquals("only held payments are asked for", listOf("HELD_FOR_REVIEW"), api.askedFor)
    }

    @Test
    fun `the same held payment is not announced again on the next check`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(held("a"))))
        val told = Told()
        val watcher = HeldWatcher(api, Remembered(), told)

        watcher.check()
        val second = watcher.check()

        assertTrue(second.isEmpty())
        assertEquals("told once", 1, told.payments.size)
    }

    @Test
    fun `a payment held after the last check is announced, and the earlier one is not repeated`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(held("a"))))
        val told = Told()
        val watcher = HeldWatcher(api, Remembered(), told)
        watcher.check()

        api.answer = ApiResult.Ok(listOf(held("b"), held("a")))
        val fresh = watcher.check()

        assertEquals(listOf("b"), fresh.map { it.id })
        assertEquals(listOf("a", "b"), told.payments.map { it.id })
    }

    @Test
    fun `a payment that has been ruled on is announced again if it is ever held again`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(held("a"))))
        val told = Told()
        val remembered = Remembered()
        val watcher = HeldWatcher(api, remembered, told)
        watcher.check()

        // Somebody approved it, so it is no longer in the held list.
        api.answer = ApiResult.Ok(emptyList())
        watcher.check()
        assertEquals("nothing is remembered that is not still held", emptySet<String>(), remembered.ids)

        api.answer = ApiResult.Ok(listOf(held("a")))
        assertEquals(listOf("a"), watcher.check().map { it.id })
        assertEquals(2, told.payments.size)
    }

    @Test
    fun `a held payment in a list already on screen is announced without asking the platform again`() = runTest {
        val api = Listing(ApiResult.Ok(emptyList()))
        val told = Told()
        val watcher = HeldWatcher(api, Remembered(), told)

        val onScreen = listOf(RemotePayment("a", "CAPTURED", null, "0000"), held("b"))
        val fresh = watcher.mention(onScreen)

        assertEquals("only the held one", listOf("b"), fresh.map { it.id })
        assertEquals(listOf("b"), told.payments.map { it.id })
        assertEquals("the platform was not asked", null, api.askedFor)
    }

    @Test
    fun `a list on screen never decides that something has stopped being held`() = runTest {
        val api = Listing(ApiResult.Ok(emptyList()))
        val told = Told()
        val remembered = Remembered().also { it.ids = setOf("older") }
        val watcher = HeldWatcher(api, remembered, told)

        watcher.mention(listOf(held("b")))

        assertEquals(
            "a held payment too old to be in this page is still remembered",
            setOf("older", "b"),
            remembered.ids,
        )
    }

    @Test
    fun `a check that could not reach the platform tells nobody anything and forgets nothing`() = runTest {
        val api = Listing(ApiResult.Ok(listOf(held("a"))))
        val told = Told()
        val remembered = Remembered()
        val watcher = HeldWatcher(api, remembered, told)
        watcher.check()

        api.answer = ApiResult.Unavailable(null, null, "no route to host")
        val fresh = watcher.check()

        assertTrue(fresh.isEmpty())
        assertEquals("still remembered, so it is not announced twice when the platform comes back", setOf("a"), remembered.ids)
        assertEquals(1, told.payments.size)
    }
}
