package dev.kauzes.mizan.merchant

import android.app.Application
import dev.kauzes.mizan.merchant.data.HttpPlatformClient
import dev.kauzes.mizan.merchant.data.PlatformClient

/**
 * Holds the one client the app talks to the platform through.
 *
 * The same shape as Sentinel Pay's application class: a single instance, created on first use, that
 * view models reach through the application rather than through a dependency injection framework.
 */
class MizanApp : Application() {
    val platform: PlatformClient by lazy { HttpPlatformClient(BuildConfig.GATEWAY_URL) }
}
