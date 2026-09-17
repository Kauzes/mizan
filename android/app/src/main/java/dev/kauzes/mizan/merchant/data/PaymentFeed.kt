package dev.kauzes.mizan.merchant.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/** The merchant's payments as the app last saw them, and what went wrong if anything did. */
data class FeedState(
    val payments: List<RemotePayment> = emptyList(),
    val loading: Boolean = true,
    /** Set when the last read failed. The payments are then the last ones that did arrive. */
    val problem: String? = null,
    val signedOut: Boolean = false,
)

/**
 * The merchant's payments, kept up to date while somebody is looking. MIZ-101.
 *
 * **It asks, repeatedly.** The platform has no stream to subscribe to: no websocket, no server-sent
 * events, no push. A list that updates without the merchant refreshing is therefore this app asking
 * every few seconds, which is what it is, and what ADR 0061 says it is.
 *
 * **Only while a screen is collecting.** The flow polls for as long as it is collected and stops when it
 * is not, so a phone in a pocket is not asking the platform anything.
 *
 * **A failed read never empties the list.** What is on screen stays, with a line saying the platform
 * could not be reached, because a till that blanks when the signal dips is worse than a stale one.
 */
class PaymentFeed(
    private val api: PaymentsApi,
    private val every: Duration = 5.seconds,
    private val howMany: Int = 25,
) {

    fun live(): Flow<FeedState> = flow {
        var state = FeedState()
        while (currentCoroutineContext().isActive) {
            state = next(state)
            emit(state)
            delay(every)
        }
    }

    /** One read, folded into what is already known. */
    suspend fun next(current: FeedState): FeedState = when (val answer = api.list(size = howMany)) {
        is ApiResult.Ok -> FeedState(payments = answer.value, loading = false)
        is ApiResult.Refused -> current.copy(
            loading = false,
            problem = answer.detail ?: "The platform refused to list payments (${answer.status}).",
        )
        is ApiResult.Unavailable -> current.copy(loading = false, problem = "Not up to date: ${answer.because}.")
        ApiResult.SignedOut -> current.copy(loading = false, signedOut = true)
    }
}
