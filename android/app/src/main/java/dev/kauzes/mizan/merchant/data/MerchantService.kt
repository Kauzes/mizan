package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AccessOutcome
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** What asking for the signed in merchant came to. */
sealed interface MerchantOutcome {
    data class Found(val id: String, val name: String) : MerchantOutcome
    data object SignedOut : MerchantOutcome
    data class Unreachable(val because: String) : MerchantOutcome
    data class Failed(val because: String) : MerchantOutcome
}

interface MerchantService {
    /** The merchant this phone is signed in as. */
    suspend fun mine(): MerchantOutcome
}

/**
 * The signed in merchant, read from identity through the gateway, with a token from [SessionManager].
 *
 * The first request the app makes with an access token, and so the one that shows renewal working: it
 * never builds an Authorization header itself, it asks the session manager for a token good for the next
 * minute, which renews it first if it has to.
 */
class HttpMerchantService(
    private val gatewayUrl: String,
    private val http: OkHttpClient,
    private val sessions: SessionManager,
) : MerchantService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun mine(): MerchantOutcome {
        val merchantId = sessions.current.value?.merchantId ?: return MerchantOutcome.SignedOut
        val token = when (val access = sessions.accessToken()) {
            is AccessOutcome.Valid -> access.token
            AccessOutcome.SignedOut -> return MerchantOutcome.SignedOut
            is AccessOutcome.Unreachable -> return MerchantOutcome.Unreachable(access.because)
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${gatewayUrl.trimEnd('/')}/api/v1/merchants/$merchantId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val merchant = json.decodeFromString(MerchantResponse.serializer(), response.body.string())
                            MerchantOutcome.Found(merchant.id, merchant.name)
                        }
                        // A token the session manager believed good for another minute and the gateway
                        // refuses: the session was ended elsewhere. Guessing otherwise would leave the
                        // merchant on a screen where every request fails.
                        response.code == 401 -> {
                            sessions.signOut()
                            MerchantOutcome.SignedOut
                        }
                        else -> MerchantOutcome.Failed("the platform answered ${response.code}")
                    }
                }
            } catch (noAnswer: IOException) {
                MerchantOutcome.Unreachable(noAnswer.message ?: noAnswer.javaClass.simpleName)
            }
        }
    }

    @Serializable
    private data class MerchantResponse(val id: String, val name: String)
}
