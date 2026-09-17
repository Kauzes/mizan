package dev.kauzes.mizan.merchant.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** On a device, because Room's SQLite is Android's. */
@RunWith(AndroidJUnit4::class)
class RoomAttemptStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MizanDatabase
    private lateinit var store: RoomAttemptStore

    private fun attempt(id: String, step: AttemptStep, createdAt: Long) = PaymentAttempt(
        id = id,
        reference = "phone-$id",
        amount = 12550,
        currency = "TRY",
        description = "two coffees",
        createKey = "create-$id",
        authorizeKey = "authorize-$id",
        captureKey = "capture-$id",
        paymentId = "payment-$id",
        cardLastFour = "0000",
        step = step,
        result = if (step == AttemptStep.FINISHED) AttemptResult.CAPTURED else null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, MizanDatabase::class.java).build()
        store = RoomAttemptStore(database.attempts())
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun anAttemptComesBackExactlyAsItWasWritten() = runBlocking {
        val written = attempt("a", AttemptStep.CAPTURING, 1_000)
        store.save(written)

        assertEquals(written, store.find("a"))
    }

    @Test
    fun savingAgainReplacesItRatherThanAddingASecond() = runBlocking {
        store.save(attempt("a", AttemptStep.CREATING, 1_000))
        store.save(attempt("a", AttemptStep.FINISHED, 1_000))

        assertEquals(AttemptStep.FINISHED, store.find("a")?.step)
        assertEquals(1, store.recent().first().size)
    }

    @Test
    fun onlyUnfinishedPaymentsAreOfferedToResume() = runBlocking {
        store.save(attempt("done", AttemptStep.FINISHED, 1_000))
        store.save(attempt("waiting", AttemptStep.CONFIRMING_AUTHORIZATION, 2_000))
        store.save(attempt("started", AttemptStep.CREATING, 3_000))

        assertEquals(listOf("waiting", "started"), store.unfinished().map { it.id })
    }

    @Test
    fun nothingStoredCouldBeACardNumber() = runBlocking {
        store.save(attempt("a", AttemptStep.CAPTURING, 1_000))

        val cursor = database.openHelper.readableDatabase.query("select * from payment_attempt")
        val everything = buildString {
            while (cursor.moveToNext()) {
                for (column in 0 until cursor.columnCount) append(cursor.getString(column)).append(' ')
            }
        }
        cursor.close()
        assertFalse("no run of twelve or more digits anywhere in the table", Regex("\\d{12,}").containsMatchIn(everything))
    }
}
