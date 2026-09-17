package dev.kauzes.mizan.merchant.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kauzes.mizan.merchant.MizanApp
import java.util.concurrent.TimeUnit

/**
 * Asks, every so often, whether anything is held for review, and tells the merchant if it is.
 *
 * This is the app's answer to not having push. It runs whether or not the app is open, on Android's own
 * schedule — fifteen minutes is the shortest period the platform will honour, and it is a floor, not a
 * promise: a dozing phone runs it later. A held payment is therefore announced within minutes of being
 * held, not instantly (ADR 0061).
 */
class HeldPaymentsWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MizanApp
        // Nobody is signed in: nothing to ask about, and no session to spend asking.
        if (app.sessions.current.value == null) return Result.success()

        return runCatching { app.heldPayments.check() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val NAME = "held-for-review"

        /** Keeps one such check scheduled. Safe to call on every launch. */
        fun keepScheduled(context: Context) {
            val work = PeriodicWorkRequestBuilder<HeldPaymentsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
