package dev.kauzes.mizan.merchant

import android.app.Application
import dev.kauzes.mizan.merchant.data.AndroidConnectivity
import dev.kauzes.mizan.merchant.data.CardVault
import dev.kauzes.mizan.merchant.data.Connectivity
import dev.kauzes.mizan.merchant.data.HttpMerchantService
import dev.kauzes.mizan.merchant.data.HttpPaymentsApi
import dev.kauzes.mizan.merchant.data.AndroidHeldNotifier
import dev.kauzes.mizan.merchant.data.HeldPaymentsWorker
import dev.kauzes.mizan.merchant.data.HeldWatcher
import dev.kauzes.mizan.merchant.data.HttpPlatformClient
import dev.kauzes.mizan.merchant.data.HttpTokenService
import dev.kauzes.mizan.merchant.data.KeystoreSessionStore
import dev.kauzes.mizan.merchant.data.MerchantService
import dev.kauzes.mizan.merchant.data.KeystoreCardVault
import dev.kauzes.mizan.merchant.data.MizanDatabase
import dev.kauzes.mizan.merchant.data.PaymentFeed
import dev.kauzes.mizan.merchant.data.PaymentSync
import dev.kauzes.mizan.merchant.data.PaymentTaker
import dev.kauzes.mizan.merchant.data.PaymentsApi
import dev.kauzes.mizan.merchant.data.PlatformClient
import dev.kauzes.mizan.merchant.data.HttpReviewsApi
import dev.kauzes.mizan.merchant.data.PrefsSeenHeld
import dev.kauzes.mizan.merchant.data.ReviewQueue
import dev.kauzes.mizan.merchant.data.ReviewsApi
import dev.kauzes.mizan.merchant.data.RoomAttemptStore
import dev.kauzes.mizan.merchant.data.SessionManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Holds the clients the app talks to the platform through.
 *
 * The same shape as Sentinel Pay's application class: single instances, created on first use, that view
 * models reach through the application rather than through a dependency injection framework.
 */
class MizanApp : Application() {

    /** One HTTP client for the whole app, so connections are shared and every call has a deadline. */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val platform: PlatformClient by lazy { HttpPlatformClient(BuildConfig.GATEWAY_URL) }

    val sessions: SessionManager by lazy {
        SessionManager(HttpTokenService(BuildConfig.GATEWAY_URL, http), KeystoreSessionStore(this))
    }

    val merchants: MerchantService by lazy { HttpMerchantService(BuildConfig.GATEWAY_URL, http, sessions) }

    val cards: CardVault by lazy { KeystoreCardVault(this) }

    val paymentsApi: PaymentsApi by lazy { HttpPaymentsApi(BuildConfig.GATEWAY_URL, http, sessions) }

    val payments: PaymentTaker by lazy {
        PaymentTaker(paymentsApi, RoomAttemptStore(MizanDatabase.get(this).attempts()), cards)
    }

    /** The merchant's payments, kept up to date while a screen is watching them. */
    val feed: PaymentFeed by lazy { PaymentFeed(paymentsApi) }

    /** The review queue, the same one the console rules on. */
    val reviews: ReviewsApi by lazy { HttpReviewsApi(BuildConfig.GATEWAY_URL, http, sessions) }

    val reviewQueue: ReviewQueue by lazy { ReviewQueue(reviews) }

    /** The check that tells the merchant about a payment held for review, on a schedule and on launch. */
    val heldPayments: HeldWatcher by lazy {
        HeldWatcher(paymentsApi, PrefsSeenHeld(this), AndroidHeldNotifier(this))
    }

    val connectivity: Connectivity by lazy { AndroidConnectivity(this) }

    /** One queue for the whole app, so the screen and the network coming back ask the same pass to run. */
    val sync: PaymentSync by lazy { PaymentSync(payments, cards) }

    override fun onCreate() {
        super.onCreate()
        // Scheduled here rather than after signing in: the schedule outlives the app, and the check
        // itself does nothing when nobody is signed in.
        HeldPaymentsWorker.keepScheduled(this)
    }
}
