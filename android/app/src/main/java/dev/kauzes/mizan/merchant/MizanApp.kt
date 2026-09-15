package dev.kauzes.mizan.merchant

import android.app.Application
import dev.kauzes.mizan.merchant.data.HttpMerchantService
import dev.kauzes.mizan.merchant.data.HttpPlatformClient
import dev.kauzes.mizan.merchant.data.HttpTokenService
import dev.kauzes.mizan.merchant.data.KeystoreSessionStore
import dev.kauzes.mizan.merchant.data.MerchantService
import dev.kauzes.mizan.merchant.data.PlatformClient
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
}
