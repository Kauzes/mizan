package dev.kauzes.mizan.merchant.ui.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import dev.kauzes.mizan.merchant.data.FeedState
import dev.kauzes.mizan.merchant.data.HeldWatcher
import dev.kauzes.mizan.merchant.data.PaymentFeed
import dev.kauzes.mizan.merchant.data.RemotePayment
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class PaymentsViewModel(feed: PaymentFeed, held: HeldWatcher) : ViewModel() {

    /**
     * The list, polled while this screen is on top and stopped when it is not.
     *
     * `WhileSubscribed` with no timeout: leaving the screen stops the asking immediately, rather than
     * politely carrying on for five seconds against a platform nobody is looking at.
     */
    val state: StateFlow<FeedState> = feed.live()
        // The scheduled check runs every fifteen minutes at best. While somebody is watching, the list is
        // already being read every few seconds, so anything held in it is mentioned straight away. Inside
        // the pipeline rather than a collector of its own, so watching nothing still asks for nothing.
        .onEach { runCatching { held.mention(it.payments) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), FeedState())

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MizanApp
                PaymentsViewModel(app.feed, app.heldPayments)
            }
        }
    }
}

@Composable
fun PaymentsScreen(viewModel: PaymentsViewModel = viewModel(factory = PaymentsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PaymentsContent(state)
}

@Composable
fun PaymentsContent(state: FeedState) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Payments", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        state.problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("problem"),
            )
        }

        when {
            state.loading && state.payments.isEmpty() ->
                Text("Loading…", modifier = Modifier.testTag("loading"))
            state.payments.isEmpty() ->
                Text("No payments yet.", modifier = Modifier.testTag("empty"))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().testTag("payments")) {
                items(state.payments, key = { it.id }) { payment ->
                    PaymentRow(payment)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: RemotePayment) {
    val money = payment.amount?.let { "${money(it)} ${payment.currency.orEmpty()}".trim() } ?: "—"
    val held = payment.status == "HELD_FOR_REVIEW"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("payment-${payment.id}")) {
        Text(money, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (held) "Held for review — waiting for a person" else payment.status.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = if (held) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        payment.cardLastFour?.let {
            Text("Card ending $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
