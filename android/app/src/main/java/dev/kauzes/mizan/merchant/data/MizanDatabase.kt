package dev.kauzes.mizan.merchant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A payment attempt as stored. Never the card: only its last four digits, once the platform returns them.
 */
@Entity(tableName = "payment_attempt")
data class PaymentAttemptEntity(
    @PrimaryKey val id: String,
    val reference: String,
    val amount: Long,
    val currency: String,
    val description: String?,
    val createKey: String,
    val authorizeKey: String,
    val captureKey: String,
    val paymentId: String?,
    val cardLastFour: String?,
    val step: String,
    val result: String?,
    val detail: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface PaymentAttemptDao {
    @Upsert
    suspend fun save(attempt: PaymentAttemptEntity)

    @Query("select * from payment_attempt where id = :id")
    suspend fun find(id: String): PaymentAttemptEntity?

    @Query("select * from payment_attempt where step != 'FINISHED' order by createdAt")
    suspend fun unfinished(): List<PaymentAttemptEntity>

    @Query("select * from payment_attempt order by createdAt desc limit 20")
    fun recent(): Flow<List<PaymentAttemptEntity>>
}

/**
 * Everything the app keeps in a database.
 *
 * No destructive fallback, unlike Sentinel Pay's. A payment attempt holds the idempotency keys that make
 * resuming it safe, and a database wiped on upgrade would turn an interrupted payment into one the app no
 * longer knows it started. A schema change here comes with a migration.
 */
@Database(entities = [PaymentAttemptEntity::class], version = 1, exportSchema = false)
abstract class MizanDatabase : RoomDatabase() {
    abstract fun attempts(): PaymentAttemptDao

    companion object {
        @Volatile
        private var instance: MizanDatabase? = null

        fun get(context: Context): MizanDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MizanDatabase::class.java, "mizan.db")
                .build()
                .also { instance = it }
        }
    }
}

/** Payment attempts in Room, in the app's own terms. */
class RoomAttemptStore(private val dao: PaymentAttemptDao) : AttemptStore {

    override suspend fun save(attempt: PaymentAttempt) = dao.save(attempt.toEntity())

    override suspend fun find(id: String) = dao.find(id)?.toAttempt()

    override suspend fun unfinished() = dao.unfinished().map { it.toAttempt() }

    override fun recent(): Flow<List<PaymentAttempt>> = dao.recent().map { rows -> rows.map { it.toAttempt() } }

    private fun PaymentAttempt.toEntity() = PaymentAttemptEntity(
        id, reference, amount, currency, description, createKey, authorizeKey, captureKey,
        paymentId, cardLastFour, step.name, result?.name, detail, createdAt, updatedAt,
    )

    private fun PaymentAttemptEntity.toAttempt() = PaymentAttempt(
        id = id,
        reference = reference,
        amount = amount,
        currency = currency,
        description = description,
        createKey = createKey,
        authorizeKey = authorizeKey,
        captureKey = captureKey,
        paymentId = paymentId,
        cardLastFour = cardLastFour,
        step = AttemptStep.valueOf(step),
        result = result?.let(AttemptResult::valueOf),
        detail = detail,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
