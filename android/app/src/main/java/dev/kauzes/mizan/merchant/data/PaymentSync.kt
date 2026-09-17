package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import dev.kauzes.mizan.merchant.domain.ProceedOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A queued payment that needs the merchant, rather than more patience. */
data class Conflict(val attempt: PaymentAttempt, val because: String)

/** What one pass over the queue came to. */
data class SyncReport(
    val finished: Int = 0,
    val stillWaiting: Int = 0,
    val conflicts: List<Conflict> = emptyList(),
    /** True when the queue stopped early because the session ended while the phone was offline. */
    val signedOut: Boolean = false,
) {
    val didSomething: Boolean get() = finished > 0 || conflicts.isNotEmpty() || signedOut
}

/**
 * Sends what was taken with no signal, once there is signal. MIZ-100.
 *
 * **In order, one at a time.** Payments are sent oldest first and sequentially, so a queue of them cannot
 * open several authorizations at once against a platform that has just come back, and so the merchant's
 * own order is the order the books see.
 *
 * **One pass at a time.** A sync runs under a lock: the network flapping, the screen opening and a manual
 * retry all ask for the same pass rather than three overlapping ones, which would send a payment's step
 * twice concurrently. The keys would make that safe at the platform; it is still not worth doing.
 *
 * **Nothing is resolved quietly.** A payment the platform refused, a reference it has already seen, a card
 * whose keeping expired and a session that ended while the phone was offline are all handed back as
 * conflicts for the merchant to see. The only thing sync decides on its own is to try again later.
 */
class PaymentSync(
    private val taker: PaymentTaker,
    private val vault: CardVault,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val running = Mutex()

    /** Sends every unfinished payment as far as it will go. Safe to call whenever; never runs twice at once. */
    suspend fun sync(): SyncReport = running.withLock {
        vault.forgetExpired(now())

        var finished = 0
        var waiting = 0
        val conflicts = mutableListOf<Conflict>()

        for (attempt in taker.unfinished().sortedBy { it.createdAt }) {
            when (val outcome = taker.proceed(attempt.id)) {
                is ProceedOutcome.Finished -> {
                    finished++
                    if (outcome.attempt.result == AttemptResult.REFUSED) {
                        conflicts += Conflict(outcome.attempt, outcome.attempt.detail ?: "The platform refused it.")
                    }
                }
                // The card was never kept, or its keeping ran out. Only the merchant can answer this.
                is ProceedOutcome.NeedsCard -> conflicts += Conflict(
                    outcome.attempt,
                    "This payment needs the card again before it can be sent.",
                )
                is ProceedOutcome.CardDiffers -> conflicts += Conflict(
                    outcome.attempt,
                    "This payment was started with a different card. Nothing has been charged.",
                )
                // Still no answer, or the platform asked for patience. The same keys go next time.
                is ProceedOutcome.Waiting -> waiting++
                is ProceedOutcome.SignedOut -> return@withLock SyncReport(
                    finished = finished,
                    stillWaiting = waiting + 1,
                    conflicts = conflicts,
                    signedOut = true,
                )
            }
        }
        SyncReport(finished, waiting, conflicts)
    }
}
