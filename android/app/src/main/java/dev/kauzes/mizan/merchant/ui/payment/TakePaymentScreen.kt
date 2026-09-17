package dev.kauzes.mizan.merchant.ui.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kauzes.mizan.merchant.MizanApp
import dev.kauzes.mizan.merchant.data.Conflict
import dev.kauzes.mizan.merchant.data.Connectivity
import dev.kauzes.mizan.merchant.data.PaymentSync
import dev.kauzes.mizan.merchant.data.PaymentTaker
import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.MoneyInput
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import dev.kauzes.mizan.merchant.domain.ProceedOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TakePaymentState(
    val amount: String = "",
    val card: String = "",
    val description: String = "",
    val running: Boolean = false,
    val message: String? = null,
    /** The interrupted payment the card field is for, when the merchant is finishing one. */
    val continuing: PaymentAttempt? = null,
    /** What the phone believes about its network. Only ever used to explain, never to judge a payment. */
    val online: Boolean = true,
    /** Payments the queue could not send by itself, and why. */
    val conflicts: List<Conflict> = emptyList(),
)

class TakePaymentViewModel(
    private val payments: PaymentTaker,
    private val sync: PaymentSync,
    connectivity: Connectivity,
) : ViewModel() {

    private val mutableState = MutableStateFlow(TakePaymentState())
    val state: StateFlow<TakePaymentState> = mutableState.asStateFlow()

    /** Payments started and not finished, including ones from before the app was last closed. */
    val unfinished: StateFlow<List<PaymentAttempt>> = payments.recent()
        .map { attempts -> attempts.filter { it.step != AttemptStep.FINISHED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Signal coming back starts a pass; the sync itself decides what is actually sendable.
        viewModelScope.launch {
            connectivity.online.collect { online ->
                mutableState.update { it.copy(online = online) }
                if (online) runSync()
            }
        }
    }

    fun onAmount(text: String) = mutableState.update { it.copy(amount = text, message = null) }
    fun onCard(text: String) = mutableState.update { it.copy(card = text.filter(Char::isDigit), message = null) }
    fun onDescription(text: String) = mutableState.update { it.copy(description = text) }

    /** Starts a new payment, or finishes the interrupted one the merchant chose, with the card entered. */
    fun submit() {
        val typed = mutableState.value
        if (typed.running) return
        val continuing = typed.continuing
        val amount = if (continuing == null) MoneyInput.minorUnits(typed.amount) else continuing.amount
        when {
            amount == null -> return say("Enter an amount, like 125.50.")
            typed.card.length !in 12..19 -> return say("Enter the card number: 12 to 19 digits.")
        }

        mutableState.update { it.copy(running = true, message = null) }
        viewModelScope.launch {
            val attemptId = continuing?.id ?: payments.start(amount!!, CURRENCY, typed.description).id
            // The card leaves the screen's state the moment it is handed over.
            val card = typed.card
            mutableState.update { it.copy(card = "") }
            show(payments.proceed(attemptId, card))
        }
    }

    /** Moves an interrupted payment on. Asks for the card only if its next step needs one. */
    fun resume(attempt: PaymentAttempt) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(running = true, message = null) }
        viewModelScope.launch { show(payments.proceed(attempt.id)) }
    }

    /** Sends everything queued now, rather than waiting for the network to say something changed. */
    fun sendQueued() {
        if (mutableState.value.running) return
        mutableState.update { it.copy(running = true, message = null) }
        viewModelScope.launch { runSync() }
    }

    private suspend fun runSync() {
        val report = sync.sync()
        mutableState.update { current ->
            current.copy(
                running = false,
                conflicts = report.conflicts,
                message = when {
                    report.signedOut -> "Signed out while offline. Sign in again to send what is waiting."
                    report.conflicts.isNotEmpty() -> "Some payments need you before they can be sent."
                    report.finished > 0 -> "Sent ${report.finished} payment${if (report.finished == 1) "" else "s"} that were waiting."
                    else -> current.message
                },
            )
        }
    }

    private fun show(outcome: ProceedOutcome) {
        val money = "${MoneyInput.format(outcome.attempt.amount)} ${outcome.attempt.currency}"
        mutableState.update {
            when (outcome) {
                is ProceedOutcome.Finished -> TakePaymentState(
                    online = it.online,
                    conflicts = it.conflicts,
                    message = finished(outcome.attempt, money),
                )
                is ProceedOutcome.NeedsCard -> it.copy(
                    running = false,
                    continuing = outcome.attempt,
                    message = "Enter the card again to finish the payment of $money.",
                )
                is ProceedOutcome.CardDiffers -> it.copy(
                    running = false,
                    continuing = outcome.attempt,
                    message = "This payment of $money was started with a different card. Enter that card to finish it; nothing has been charged to this one.",
                )
                // Queued: the card was kept, so this one needs nothing further from the merchant.
                is ProceedOutcome.Waiting -> TakePaymentState(
                    online = it.online,
                    conflicts = it.conflicts,
                    message = if (it.online) outcome.because else "$money is waiting to be sent. It will go by itself when there is signal.",
                )
                is ProceedOutcome.SignedOut -> it.copy(running = false, message = "Signed out. Sign in again to finish this payment.")
            }
        }
    }

    private fun finished(attempt: PaymentAttempt, money: String): String = when (attempt.result) {
        AttemptResult.CAPTURED -> "Paid: $money" + (attempt.cardLastFour?.let { ", card ending $it." } ?: ".")
        AttemptResult.HELD_FOR_REVIEW -> "Held for review: $money. Nobody has been charged."
        AttemptResult.DECLINED -> "Declined: ${attempt.detail ?: "the card was refused"}."
        AttemptResult.REFUSED, null -> "Not taken: ${attempt.detail ?: "the platform refused it"}."
    }

    private fun say(message: String) = mutableState.update { it.copy(message = message) }

    companion object {
        /** The currency every account on this platform is opened in today. */
        const val CURRENCY = "TRY"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MizanApp
                TakePaymentViewModel(app.payments, app.sync, app.connectivity)
            }
        }
    }
}

@Composable
fun TakePaymentScreen(viewModel: TakePaymentViewModel = viewModel(factory = TakePaymentViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unfinished by viewModel.unfinished.collectAsStateWithLifecycle()
    TakePaymentContent(
        state = state,
        unfinished = unfinished,
        onAmount = viewModel::onAmount,
        onCard = viewModel::onCard,
        onDescription = viewModel::onDescription,
        onSubmit = viewModel::submit,
        onResume = viewModel::resume,
        onSendQueued = viewModel::sendQueued,
    )
}

@Composable
fun TakePaymentContent(
    state: TakePaymentState,
    unfinished: List<PaymentAttempt>,
    onAmount: (String) -> Unit,
    onCard: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSubmit: () -> Unit,
    onResume: (PaymentAttempt) -> Unit,
    onSendQueued: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            if (state.continuing == null) "Take a payment" else "Finish a payment",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (!state.online) {
            Text(
                "No signal. Payments can still be taken; they are sent when signal returns.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("offline"),
            )
        }

        if (state.continuing == null) {
            OutlinedTextField(
                value = state.amount,
                onValueChange = onAmount,
                label = { Text("Amount (TRY)") },
                singleLine = true,
                enabled = !state.running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("amount"),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescription,
                label = { Text("What it is for (optional)") },
                singleLine = true,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth().testTag("description"),
            )
        } else {
            Text(
                "${MoneyInput.format(state.continuing.amount)} ${state.continuing.currency}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("continuing"),
            )
        }

        OutlinedTextField(
            value = state.card,
            onValueChange = onCard,
            label = { Text("Card number") },
            singleLine = true,
            enabled = !state.running,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().testTag("card"),
        )

        Button(onClick = onSubmit, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    state.running -> "Working…"
                    state.continuing != null -> "Finish payment"
                    else -> "Take payment"
                },
            )
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("result"))
        }

        state.conflicts.forEach { conflict ->
            Text(
                "${MoneyInput.format(conflict.attempt.amount)} ${conflict.attempt.currency}: ${conflict.because}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("conflict-${conflict.attempt.id}"),
            )
        }

        if (unfinished.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Waiting to be sent (${unfinished.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("waiting"),
            )
            unfinished.forEach { attempt ->
                OutlinedButton(
                    onClick = { onResume(attempt) },
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth().testTag("resume-${attempt.id}"),
                ) {
                    Text("Continue ${MoneyInput.format(attempt.amount)} ${attempt.currency}")
                }
            }
            OutlinedButton(
                onClick = onSendQueued,
                enabled = !state.running && state.online,
                modifier = Modifier.fillMaxWidth().testTag("send-queued"),
            ) {
                Text("Send all now")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
