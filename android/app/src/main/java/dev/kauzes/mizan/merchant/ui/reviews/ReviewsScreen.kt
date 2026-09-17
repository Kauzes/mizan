package dev.kauzes.mizan.merchant.ui.reviews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kauzes.mizan.merchant.MizanApp
import dev.kauzes.mizan.merchant.data.ApiResult
import dev.kauzes.mizan.merchant.data.QueueState
import dev.kauzes.mizan.merchant.data.RemotePayment
import dev.kauzes.mizan.merchant.data.ReviewQueue
import dev.kauzes.mizan.merchant.data.ReviewsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the merchant is typing and what came back, alongside the queue itself. */
data class RulingState(
    val why: Map<String, String> = emptyMap(),
    /** The payment a ruling is in flight for; nothing else can be ruled on meanwhile. */
    val busy: String? = null,
    val refusal: String? = null,
    val ruled: String? = null,
)

class ReviewsViewModel(queue: ReviewQueue, private val reviews: ReviewsApi) : ViewModel() {

    private val rulings = MutableStateFlow(RulingState())
    val ruling: StateFlow<RulingState> = rulings.asStateFlow()

    /** Payments ruled on here, hidden at once rather than waiting for the next read to drop them. */
    private val goneFromHere = MutableStateFlow(emptySet<String>())

    val state: StateFlow<QueueState> = queue.live()
        .combine(goneFromHere) { queue, gone -> queue.copy(waiting = queue.waiting.filterNot { it.id in gone }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), QueueState())

    fun onWhy(paymentId: String, text: String) = rulings.update {
        it.copy(why = it.why + (paymentId to text), refusal = null)
    }

    fun release(payment: RemotePayment) =
        rule(payment, "Approved. It can be taken to the bank again.") { why -> reviews.release(payment.id, why) }

    fun refuse(payment: RemotePayment) =
        rule(payment, "Declined. Nobody has been charged, and now nobody will be.") { why -> reviews.refuse(payment.id, why) }

    /**
     * [ruled] says what the merchant decided, not what the payment's status became.
     *
     * Releasing records the ruling and leaves the payment held until it is authorized again, so reading
     * the status back at somebody would tell a merchant who just approved a payment that it is still held.
     */
    private fun rule(payment: RemotePayment, ruled: String, call: suspend (String) -> ApiResult<RemotePayment>) {
        val why = rulings.value.why[payment.id].orEmpty().trim()
        if (why.isEmpty() || rulings.value.busy != null) return

        rulings.update { it.copy(busy = payment.id, refusal = null, ruled = null) }
        viewModelScope.launch {
            when (val answer = call(why)) {
                is ApiResult.Ok -> {
                    goneFromHere.update { it + payment.id }
                    rulings.update { it.copy(busy = null, why = it.why - payment.id, ruled = ruled) }
                }
                // Somebody ruled on it first, or the platform will not take this ruling. Its sentence is
                // better than anything this app could invent, so it is shown as it came.
                is ApiResult.Refused -> rulings.update {
                    it.copy(busy = null, refusal = answer.detail ?: "The platform refused it (${answer.status}).")
                }
                is ApiResult.Unavailable -> rulings.update {
                    it.copy(busy = null, refusal = "The platform could not be reached: ${answer.because}. Nothing was ruled.")
                }
                ApiResult.SignedOut -> rulings.update {
                    it.copy(busy = null, refusal = "Signed out. Sign in again to rule on this payment.")
                }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MizanApp
                ReviewsViewModel(app.reviewQueue, app.reviews)
            }
        }
    }
}

@Composable
fun ReviewsScreen(viewModel: ReviewsViewModel = viewModel(factory = ReviewsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ruling by viewModel.ruling.collectAsStateWithLifecycle()
    ReviewsContent(
        state = state,
        ruling = ruling,
        onWhy = viewModel::onWhy,
        onRelease = viewModel::release,
        onRefuse = viewModel::refuse,
    )
}

@Composable
fun ReviewsContent(
    state: QueueState,
    ruling: RulingState,
    onWhy: (String, String) -> Unit,
    onRelease: (RemotePayment) -> Unit,
    onRefuse: (RemotePayment) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Review queue", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        when {
            state.notAllowed -> Text(
                "This account does not rule on held payments.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("not-allowed"),
            )
            else -> {
                state.problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("problem"),
                    )
                }
                ruling.refusal?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("refusal"),
                    )
                }
                ruling.ruled?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ruled"))
                }

                if (state.loading && state.waiting.isEmpty()) {
                    Text("Looking…", modifier = Modifier.testTag("loading"))
                } else if (state.waiting.isEmpty()) {
                    Text(
                        "Nothing is waiting. Every held payment has been ruled on.",
                        modifier = Modifier.testTag("empty"),
                    )
                }

                state.waiting.forEach { payment ->
                    Held(
                        payment = payment,
                        why = ruling.why[payment.id].orEmpty(),
                        busy = ruling.busy != null,
                        onWhy = { onWhy(payment.id, it) },
                        onRelease = { onRelease(payment) },
                        onRefuse = { onRefuse(payment) },
                    )
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Held(
    payment: RemotePayment,
    why: String,
    busy: Boolean,
    onWhy: (String) -> Unit,
    onRelease: () -> Unit,
    onRefuse: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("held-${payment.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            payment.amount?.let { "${money(it)} ${payment.currency.orEmpty()}".trim() } ?: "A payment",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            payment.riskReasons ?: "Risk held it for a person to look at.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = why,
            onValueChange = onWhy,
            label = { Text("Why") },
            singleLine = false,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("why-${payment.id}"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            // A ruling without a reason is not a ruling anybody can audit, and the platform refuses it, so
            // the button is not offered until there is one.
            Button(
                onClick = onRelease,
                enabled = !busy && why.isNotBlank(),
                modifier = Modifier.weight(1f).testTag("release-${payment.id}"),
            ) { Text("Approve") }
            OutlinedButton(
                onClick = onRefuse,
                enabled = !busy && why.isNotBlank(),
                modifier = Modifier.weight(1f).testTag("refuse-${payment.id}"),
            ) { Text("Decline") }
        }
    }
}

private fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
