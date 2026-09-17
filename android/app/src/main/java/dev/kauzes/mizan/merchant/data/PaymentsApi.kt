package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AccessOutcome
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A payment as the platform describes it: only what taking one needs. */
data class RemotePayment(
    val id: String,
    val status: String,
    val declineReason: String?,
    val cardLastFour: String?,
)

/** What one request to the platform came to, in terms the payment steps can act on. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** The platform answered and refused (a 4xx), with its problem code: CONFLICT, UNPROCESSABLE and so on. */
    data class Refused(val status: Int, val code: String?, val detail: String?) : ApiResult<Nothing>

    /**
     * No usable answer: a 5xx, or nothing at all. [code] tells the two cases that matter apart. On
     * UPSTREAM_TIMEOUT the request reached the acquirer and nobody knows what it did. On
     * UPSTREAM_UNAVAILABLE, or no response, it may not have been acted on.
     */
    data class Unavailable(val status: Int?, val code: String?, val because: String) : ApiResult<Nothing>

    data object SignedOut : ApiResult<Nothing>
}

/** The payment endpoints this app uses, for the signed in merchant. */
interface PaymentsApi {
    suspend fun create(key: String, amount: Long, currency: String, reference: String, description: String?): ApiResult<RemotePayment>
    suspend fun authorize(paymentId: String, key: String, card: String): ApiResult<RemotePayment>
    suspend fun capture(paymentId: String, key: String): ApiResult<RemotePayment>
    suspend fun find(paymentId: String): ApiResult<RemotePayment>
}

/**
 * The payment endpoints through the gateway, with a token from [SessionManager].
 *
 * The card goes in one request body, to the gateway over the connection the network security config
 * allows, and nowhere else: not a log, not storage, not a field of any object that outlives the call.
 */
class HttpPaymentsApi(
    private val gatewayUrl: String,
    private val http: OkHttpClient,
    private val sessions: SessionManager,
) : PaymentsApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(
        key: String, amount: Long, currency: String, reference: String, description: String?,
    ) = send("", key, json.encodeToString(CreateBody.serializer(), CreateBody(amount, currency, reference, description)))

    override suspend fun authorize(paymentId: String, key: String, card: String) =
        send("/$paymentId/authorize", key, json.encodeToString(AuthorizeBody.serializer(), AuthorizeBody(card)))

    override suspend fun capture(paymentId: String, key: String) = send("/$paymentId/capture", key, null)

    override suspend fun find(paymentId: String) = send("/$paymentId", key = null, body = null)

    /** POST when there is a key, GET when there is not; the answer classified the same way for both. */
    private suspend fun send(path: String, key: String?, body: String?): ApiResult<RemotePayment> {
        val merchantId = sessions.current.value?.merchantId ?: return ApiResult.SignedOut
        val token = when (val access = sessions.accessToken()) {
            is AccessOutcome.Valid -> access.token
            AccessOutcome.SignedOut -> return ApiResult.SignedOut
            is AccessOutcome.Unreachable -> return ApiResult.Unavailable(null, null, access.because)
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${gatewayUrl.trimEnd('/')}/api/v1/merchants/$merchantId/payments$path")
                .header("Authorization", "Bearer $token")
                .apply {
                    if (key == null) {
                        get()
                    } else {
                        header("Idempotency-Key", key)
                        post((body ?: "").toRequestBody(JSON))
                    }
                }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when {
                        response.isSuccessful -> ApiResult.Ok(paymentFrom(text))
                        response.code == 401 -> {
                            sessions.signOut()
                            ApiResult.SignedOut
                        }
                        response.code in 400..499 -> problem(text).let { ApiResult.Refused(response.code, it.first, it.second) }
                        else -> problem(text).let {
                            ApiResult.Unavailable(response.code, it.first, it.second ?: "the platform answered ${response.code}")
                        }
                    }
                }
            } catch (noAnswer: IOException) {
                ApiResult.Unavailable(null, null, noAnswer.message ?: noAnswer.javaClass.simpleName)
            }
        }
    }

    private fun paymentFrom(text: String): RemotePayment {
        val payment = json.decodeFromString(PaymentBody.serializer(), text)
        return RemotePayment(payment.id, payment.status, payment.declineReason, payment.cardLastFour)
    }

    /** The platform's problem detail: its code and its sentence, when it sent one. */
    private fun problem(text: String): Pair<String?, String?> = runCatching {
        val body: JsonObject = json.parseToJsonElement(text).jsonObject
        body["code"]?.jsonPrimitive?.content to body["detail"]?.jsonPrimitive?.content
    }.getOrDefault(null to null)

    @Serializable
    private data class CreateBody(val amount: Long, val currency: String, val reference: String, val description: String?)

    @Serializable
    private data class AuthorizeBody(val card: String) {
        override fun toString() = "AuthorizeBody(card=****)"
    }

    @Serializable
    private data class PaymentBody(
        val id: String,
        val status: String,
        val declineReason: String? = null,
        val cardLastFour: String? = null,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
