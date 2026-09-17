package dev.kauzes.mizan.merchant.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * The review queue, as the console uses it. MIZ-102.
 *
 * The same four calls the console makes (`console/src/reviews/Reviews.tsx`): the queue, and releasing or
 * refusing one payment with a reason. There is no phone-shaped copy of any of this on the platform, which
 * is the point — two clients, one queue, one set of rules about who may rule and what happens when two
 * people rule at once.
 */
interface ReviewsApi {
    /** Payments held for review, waiting for a person. */
    suspend fun waiting(): ApiResult<List<RemotePayment>>

    /** Let it through. [why] is required by the platform and kept with the ruling. */
    suspend fun release(paymentId: String, why: String): ApiResult<RemotePayment>

    /** Refuse it. Nobody is charged. */
    suspend fun refuse(paymentId: String, why: String): ApiResult<RemotePayment>
}

class HttpReviewsApi(
    gatewayUrl: String,
    http: OkHttpClient,
    sessions: SessionManager,
) : ReviewsApi {

    private val calls = MerchantCalls(gatewayUrl, http, sessions)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun waiting() = calls.get("/reviews") { Payments.many(it) }

    override suspend fun release(paymentId: String, why: String) = rule(paymentId, "release", why)

    override suspend fun refuse(paymentId: String, why: String) = rule(paymentId, "refuse", why)

    private suspend fun rule(paymentId: String, verb: String, why: String) =
        calls.post("/reviews/$paymentId/$verb", json.encodeToString(Why.serializer(), Why(why.trim()))) {
            Payments.one(it)
        }

    @Serializable
    private data class Why(val why: String)
}

/** The queue as the screen sees it. */
data class QueueState(
    val waiting: List<RemotePayment> = emptyList(),
    val loading: Boolean = true,
    val problem: String? = null,
    /** True when this account may not rule on held payments at all. */
    val notAllowed: Boolean = false,
    val signedOut: Boolean = false,
)

/**
 * The review queue, kept current while somebody is looking at it, the same way the payments list is.
 *
 * It matters more here than it does there: two people can be looking at one queue, and the one who did not
 * rule should see the payment leave rather than find out by being refused.
 */
class ReviewQueue(private val api: ReviewsApi, private val every: Duration = 5.seconds) {

    fun live(): Flow<QueueState> = flow {
        var state = QueueState()
        while (currentCoroutineContext().isActive) {
            state = next(state)
            emit(state)
            delay(every)
        }
    }

    suspend fun next(current: QueueState): QueueState = when (val answer = api.waiting()) {
        is ApiResult.Ok -> QueueState(waiting = answer.value, loading = false)
        is ApiResult.Refused ->
            if (answer.status == 403) {
                QueueState(loading = false, notAllowed = true)
            } else {
                current.copy(loading = false, problem = answer.detail ?: "The platform refused it (${answer.status}).")
            }
        is ApiResult.Unavailable -> current.copy(loading = false, problem = "Not up to date: ${answer.because}.")
        ApiResult.SignedOut -> current.copy(loading = false, signedOut = true)
    }
}
