package dev.kauzes.mizan.merchant.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.kauzes.mizan.merchant.data.SessionManager
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val message: String? = null,
)

class SignInViewModel(private val sessions: SessionManager) : ViewModel() {

    private val mutableState = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = mutableState.asStateFlow()

    fun onEmail(email: String) = mutableState.update { it.copy(email = email, message = null) }
    fun onPassword(password: String) = mutableState.update { it.copy(password = password, message = null) }

    fun submit(onSignedIn: () -> Unit) {
        val typed = mutableState.value
        if (typed.submitting) return
        if (typed.email.isBlank() || typed.password.isBlank()) {
            mutableState.update { it.copy(message = "Enter the email and password for your account.") }
            return
        }

        mutableState.update { it.copy(submitting = true, message = null) }
        viewModelScope.launch {
            val outcome = sessions.signIn(typed.email.trim(), typed.password)
            // The password leaves memory the moment it has been used, whatever the answer was.
            mutableState.update { it.copy(submitting = false, password = "", message = messageFor(outcome)) }
            if (outcome is SignInOutcome.SignedIn) onSignedIn()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SignInViewModel((this[APPLICATION_KEY] as MizanApp).sessions) }
        }

        /** Says what happened in the merchant's terms, and never which of email or password was wrong. */
        fun messageFor(outcome: SignInOutcome): String? = when (outcome) {
            is SignInOutcome.SignedIn -> null
            SignInOutcome.Refused -> "That email and password do not match an account."
            is SignInOutcome.Unreachable -> "The platform could not be reached. Check this phone's connection, and try again."
            is SignInOutcome.Failed -> "Signing in did not work: ${outcome.because}."
        }
    }
}

@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    onCheckPlatform: () -> Unit,
    viewModel: SignInViewModel = viewModel(factory = SignInViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SignInContent(
        state = state,
        onEmail = viewModel::onEmail,
        onPassword = viewModel::onPassword,
        onSubmit = { viewModel.submit(onSignedIn) },
        onCheckPlatform = onCheckPlatform,
    )
}

@Composable
fun SignInContent(
    state: SignInState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onCheckPlatform: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Mizan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Sign in with the account your business uses on the console.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmail,
            label = { Text("Email") },
            singleLine = true,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )

        Button(onClick = onSubmit, enabled = !state.submitting, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.submitting) "Signing in…" else "Sign in")
        }

        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("message"),
            )
        }

        TextButton(onClick = onCheckPlatform) { Text("Check the platform") }
    }
}
