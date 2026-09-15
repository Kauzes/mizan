package dev.kauzes.mizan.merchant.ui.signin

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import dev.kauzes.mizan.merchant.theme.MizanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typingAndSubmittingReachTheViewModel() {
        var email = ""
        var submitted = 0
        compose.setContent {
            MizanTheme {
                SignInContent(
                    state = SignInState(),
                    onEmail = { email = it },
                    onPassword = {},
                    onSubmit = { submitted++ },
                    onCheckPlatform = {},
                )
            }
        }

        compose.onNodeWithTag("email").performTextInput("owner@kauzes.dev")
        compose.onNodeWithText("Sign in").performClick()

        assertEquals("owner@kauzes.dev", email)
        assertEquals(1, submitted)
    }

    @Test
    fun whileSigningInNothingCanBeSentAgain() {
        compose.setContent {
            MizanTheme {
                SignInContent(SignInState(submitting = true), {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("Signing in…").assertIsNotEnabled()
    }

    @Test
    fun aRefusalSaysSoWithoutSayingWhichFieldWasWrong() {
        val refused = SignInViewModel.messageFor(SignInOutcome.Refused)
        compose.setContent {
            MizanTheme { SignInContent(SignInState(message = refused), {}, {}, {}, {}) }
        }

        compose.onNodeWithTag("message").assertTextContains("do not match an account", substring = true)
    }
}
