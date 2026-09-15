package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.PlatformHealth
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the app asks of the platform, behind one interface so view models can be tested. */
interface PlatformClient {

    /** The gateway this client talks to, shown to the merchant so a wrong address is obvious. */
    val gatewayUrl: String

    /** Asks the gateway whether the platform is up. Never throws. */
    suspend fun health(): PlatformHealth
}

/**
 * The platform, over HTTP, through its gateway.
 *
 * The standard library's client on purpose, for now. The one call this app makes so far is a health
 * check, and choosing an HTTP library is a decision that belongs with the first call that needs one:
 * signing in, with its token refresh (MIZ-98).
 */
class HttpPlatformClient(
    override val gatewayUrl: String,
    private val timeoutMillis: Int = 5_000,
) : PlatformClient {

    override suspend fun health(): PlatformHealth = withContext(Dispatchers.IO) {
        val connection = try {
            URL("${gatewayUrl.trimEnd('/')}/actuator/health").openConnection() as HttpURLConnection
        } catch (malformed: IOException) {
            return@withContext PlatformHealth.Unreachable(describe(malformed))
        } catch (malformed: IllegalArgumentException) {
            return@withContext PlatformHealth.Unreachable("not an address: $gatewayUrl")
        }

        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            // Boot's health answer. Read by looking for the status rather than parsing JSON, which
            // this check does not otherwise need a library for.
            if (status == 200 && body.contains("\"status\":\"UP\"")) {
                PlatformHealth.Up
            } else {
                PlatformHealth.Down("the gateway answered $status")
            }
        } catch (noAnswer: IOException) {
            PlatformHealth.Unreachable(describe(noAnswer))
        } catch (refused: SecurityException) {
            PlatformHealth.Unreachable("this device refused the connection: ${refused.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(failure: Exception): String =
        failure.message?.let { "${failure.javaClass.simpleName}: $it" } ?: failure.javaClass.simpleName
}
