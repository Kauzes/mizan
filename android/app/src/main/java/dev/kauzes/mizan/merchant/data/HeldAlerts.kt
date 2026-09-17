package dev.kauzes.mizan.merchant.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Tells the merchant, on the phone, about a payment that is waiting for a person. */
interface HeldNotifier {
    fun tell(payment: RemotePayment)
}

/** The held payments this phone has already told the merchant about. */
interface SeenHeld {
    suspend fun seen(): Set<String>
    suspend fun remember(ids: Set<String>)
}

/**
 * Finds payments held for review that this phone has not mentioned yet, and mentions them once.
 *
 * Once, because the check runs on a schedule and on every launch: without remembering, the same held
 * payment would be announced every fifteen minutes until somebody ruled on it.
 */
class HeldWatcher(
    private val api: PaymentsApi,
    private val seen: SeenHeld,
    private val notifier: HeldNotifier,
) {

    /**
     * Asks the platform what is held and mentions anything new.
     *
     * This is the full check: what it does not find is no longer held, so it is also what forgets a
     * payment somebody has ruled on. Returns what it told the merchant about.
     */
    suspend fun check(): List<RemotePayment> {
        val held = when (val answer = api.list(statuses = listOf(HELD), size = HOW_MANY)) {
            is ApiResult.Ok -> answer.value
            // Nothing to tell anybody: the next run asks again, and nothing has been marked as seen.
            else -> return emptyList()
        }

        val fresh = mention(held)
        // Only what is still held is remembered. A payment that has been ruled on drops out of the list,
        // and if it is ever held again it is worth mentioning again.
        seen.remember(held.map { it.id }.toSet())
        return fresh
    }

    /**
     * Mentions anything held in a list somebody is already looking at, without asking the platform again.
     *
     * The live list polls every few seconds, so this is what makes a payment held while the merchant
     * watches arrive as a notification rather than waiting for the next scheduled check. It only ever adds
     * to what has been mentioned: this list is the most recent payments, not every held one, so it is not
     * in a position to decide that anything has stopped being held. [check] does that.
     */
    suspend fun mention(payments: List<RemotePayment>): List<RemotePayment> {
        val known = seen.seen()
        val fresh = payments.filter { it.status == HELD && it.id !in known }
        if (fresh.isEmpty()) return emptyList()

        fresh.forEach(notifier::tell)
        seen.remember(known + fresh.map { it.id })
        return fresh
    }

    private companion object {
        const val HELD = "HELD_FOR_REVIEW"
        const val HOW_MANY = 50
    }
}

/** Remembered in ordinary preferences: which payments were mentioned is not a secret. */
class PrefsSeenHeld(context: Context) : SeenHeld {

    private val preferences = context.getSharedPreferences("held-seen", Context.MODE_PRIVATE)

    override suspend fun seen(): Set<String> = preferences.getStringSet(KEY, emptySet()).orEmpty()

    override suspend fun remember(ids: Set<String>) {
        preferences.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "ids"
    }
}

/**
 * An ordinary Android notification, raised by this app.
 *
 * Not a push. There is no Firebase project or credentials in this repository, so nothing can be delivered
 * from a server to this phone; what raises this is the app's own periodic check (ADR 0061). The practical
 * difference is delay: a held payment is announced at the next check, not the instant risk decides.
 */
class AndroidHeldNotifier(private val context: Context) : HeldNotifier {

    override fun tell(payment: RemotePayment) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Payments held for review", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a payment needs a person to look at it before it goes through."
            },
        )

        val money = payment.amount?.let { amount ->
            "${MoneyText.format(amount)} ${payment.currency.orEmpty()}".trim()
        } ?: "A payment"

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = open?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Held for review")
            .setContentText("$money is waiting for a person. Nobody has been charged.")
            .setAutoCancel(true)
            .apply { tap?.let(::setContentIntent) }
            .build()

        // Keyed on the payment, so the same held payment never stacks up as several notifications.
        runCatching { manager.notify(payment.id.hashCode(), notification) }
    }

    private companion object {
        const val CHANNEL = "held-for-review"
    }
}

/** Minor units as money, without dragging the payment screen's input parsing in here. */
internal object MoneyText {
    fun format(minor: Long, fractionDigits: Int = 2): String {
        val scale = generateSequence(1L) { it * 10 }.take(fractionDigits + 1).last()
        val whole = minor / scale
        val rest = (minor % scale).toString().padStart(fractionDigits, '0')
        return if (fractionDigits == 0) "$whole" else "$whole.$rest"
    }
}
