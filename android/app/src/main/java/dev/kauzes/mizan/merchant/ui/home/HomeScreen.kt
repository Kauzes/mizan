package dev.kauzes.mizan.merchant.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.kauzes.mizan.merchant.data.MerchantOutcome
import dev.kauzes.mizan.merchant.data.MerchantService
import dev.kauzes.mizan.merchant.data.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val merchantName: String? = null,
    val message: String? = null,
)

class HomeViewModel(private val merchants: MerchantService, private val sessions: SessionManager) : ViewModel() {

    private val mutableState = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutableState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        mutableState.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            mutableState.update { current ->
                when (val outcome = merchants.mine()) {
                    is MerchantOutcome.Found -> current.copy(loading = false, merchantName = outcome.name)
                    // Navigation takes the merchant to sign in; nothing to show here.
                    MerchantOutcome.SignedOut -> current.copy(loading = false)
                    is MerchantOutcome.Unreachable -> current.copy(
                        loading = false,
                        message = "The platform could not be reached. You are still signed in.",
                    )
                    is MerchantOutcome.Failed -> current.copy(loading = false, message = "Could not load your business: ${outcome.because}.")
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { sessions.signOut() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MizanApp
                HomeViewModel(app.merchants, app.sessions)
            }
        }
    }
}

@Composable
fun HomeScreen(
    onTakePayment: () -> Unit,
    onSeePayments: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onTakePayment = onTakePayment,
        onSeePayments = onSeePayments,
        onReload = viewModel::reload,
        onSignOut = viewModel::signOut,
    )
}

@Composable
fun HomeContent(
    state: HomeState,
    onTakePayment: () -> Unit,
    onSeePayments: () -> Unit,
    onReload: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Signed in", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when {
                state.merchantName != null -> state.merchantName
                state.loading -> "Loading your business…"
                else -> "Your business"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("merchant"),
        )

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("message"))
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onTakePayment, modifier = Modifier.fillMaxWidth()) { Text("Take a payment") }
        OutlinedButton(onClick = onSeePayments, modifier = Modifier.fillMaxWidth()) { Text("Payments") }
        OutlinedButton(onClick = onReload, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Reload") }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
