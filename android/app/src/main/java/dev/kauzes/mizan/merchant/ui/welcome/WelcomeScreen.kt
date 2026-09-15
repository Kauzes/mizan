package dev.kauzes.mizan.merchant.ui.welcome

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
import dev.kauzes.mizan.merchant.data.PlatformClient
import dev.kauzes.mizan.merchant.domain.PlatformHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WelcomeState(
    val gatewayUrl: String,
    val checking: Boolean = false,
    val health: PlatformHealth? = null,
)

class WelcomeViewModel(private val platform: PlatformClient) : ViewModel() {

    private val mutableState = MutableStateFlow(WelcomeState(gatewayUrl = platform.gatewayUrl))
    val state: StateFlow<WelcomeState> = mutableState.asStateFlow()

    /** Asks the platform whether it is up. A second tap while the first is still asking does nothing. */
    fun check() {
        if (mutableState.value.checking) return
        mutableState.update { it.copy(checking = true) }
        viewModelScope.launch {
            val health = platform.health()
            mutableState.update { it.copy(checking = false, health = health) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { WelcomeViewModel((this[APPLICATION_KEY] as MizanApp).platform) }
        }
    }
}

@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = viewModel(factory = WelcomeViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WelcomeContent(state = state, onCheck = viewModel::check)
}

/** The screen itself, apart from where its state comes from, so a UI test can give it any state. */
@Composable
fun WelcomeContent(state: WelcomeState, onCheck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Mizan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Take payments in person, and see what became of them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text("Platform", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            state.gatewayUrl,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("gateway"),
        )

        Button(onClick = onCheck, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.checking) "Checking…" else "Check the platform")
        }

        state.health?.let { health ->
            Text(
                describe(health),
                style = MaterialTheme.typography.bodyMedium,
                color = when (health) {
                    PlatformHealth.Up -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.testTag("health"),
            )
        }
    }
}

private fun describe(health: PlatformHealth): String = when (health) {
    PlatformHealth.Up -> "The platform is up."
    is PlatformHealth.Down -> "The platform answered, but is not up: ${health.because}."
    is PlatformHealth.Unreachable -> "Nothing answered at this address: ${health.because}."
}
