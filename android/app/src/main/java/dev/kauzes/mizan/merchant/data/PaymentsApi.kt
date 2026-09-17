package dev.kauzes.mizan.merchant.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Reading the platform's payments, shared by everything here that is sent one. */
internal object Payments {

    private val json = Json { ignoreUnknownKeys = true }

    fun one(text: String): RemotePayment = json.decodeFromString(Body.serializer(), text).toRemote()

    fun many(text: String): List<RemotePayment> =
        json.decodeFromString(ListSerializer(Body.serializer()), text).map { it.toRemote() }

    @Serializable
    private data class Body(
        val id: String,
        val status: String,
        val declineReason: String? = null,
        val cardLastFour: String? = null,
        val amount: Long? = null,
        val currency: String? = null,
        val reference: String? = null,
        val riskVerdict: String? = null,
        val riskReasons: String? = null,
        val createdAt: String? = null,
    ) {
        fun toRemote() = RemotePayment(
            id, status, declineReason, cardLastFour, amount, currency, reference, riskVerdict, riskReasons, createdAt,
        )
    }
}

/**
 * A payment as the platform describes it.
 *
 * The fields a list needs are nullable and default to nothing, because taking a payment only ever reads
 * the first four and a client that asked for one payment should not depend on the rest being there.
 */
data class RemotePayment(
    val id: String,
    val status: String,
    val declineReason: String?,
    val cardLastFour: String?,
    val amount: Long? = null,
    val currency: String? = null,
    val reference: String? = null,
    val riskVerdict: String? = null,
    /** Why risk held or scored it, in the platform's own words. */
    val riskReasons: String? = null,
    val createdAt: String? = null,
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

    /** This merchant's payments, most recent first. [statuses] narrows to those statuses when given. */
    suspend fun list(statuses: List<String> = emptyList(), size: Int = 25): ApiResult<List<RemotePayment>>
}

/**
 * The payment endpoints through the gateway, with a token from [SessionManager].
 *
 * The card goes in one request body, to the gateway over the connection the network security config
 * allows, and nowhere else: not a log, not storage, not a field of any object that outlives the call.
 */
class HttpPaymentsApi(
    gatewayUrl: String,
    http: OkHttpClient,
    sessions: SessionManager,
) : PaymentsApi {

    private val calls = MerchantCalls(gatewayUrl, http, sessions)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(
        key: String, amount: Long, currency: String, reference: String, description: String?,
    ) = calls.post(
        "/payments",
        json.encodeToString(CreateBody.serializer(), CreateBody(amount, currency, reference, description)),
        key,
    ) { paymentFrom(it) }

    override suspend fun authorize(paymentId: String, key: String, card: String) = calls.post(
        "/payments/$paymentId/authorize",
        json.encodeToString(AuthorizeBody.serializer(), AuthorizeBody(card)),
        key,
    ) { paymentFrom(it) }

    override suspend fun capture(paymentId: String, key: String) =
        calls.post("/payments/$paymentId/capture", body = null, key = key) { paymentFrom(it) }

    override suspend fun find(paymentId: String) = calls.get("/payments/$paymentId") { paymentFrom(it) }

    override suspend fun list(statuses: List<String>, size: Int): ApiResult<List<RemotePayment>> {
        val query = buildString {
            append("/payments?size=").append(size)
            statuses.forEach { append("&status=").append(it) }
        }
        return calls.get(query) { paymentsFrom(it) }
    }

    private fun paymentFrom(text: String) = Payments.one(text)

    private fun paymentsFrom(text: String) = Payments.many(text)

    @Serializable
    private data class CreateBody(val amount: Long, val currency: String, val reference: String, val description: String?)

    @Serializable
    private data class AuthorizeBody(val card: String) {
        override fun toString() = "AuthorizeBody(card=****)"
    }



}
