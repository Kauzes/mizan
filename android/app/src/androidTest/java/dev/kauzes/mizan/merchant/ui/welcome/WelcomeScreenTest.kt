package dev.kauzes.mizan.merchant.ui.welcome

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kauzes.mizan.merchant.domain.PlatformHealth
import dev.kauzes.mizan.merchant.theme.MizanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsWhichPlatformItTalksTo() {
        compose.setContent {
            MizanTheme { WelcomeContent(state = WelcomeState("http://10.0.2.2:8080"), onCheck = {}) }
        }

        compose.onNodeWithTag("gateway").assertTextContains("http://10.0.2.2:8080")
        compose.onNodeWithText("Check the platform").assertIsDisplayed()
    }

    @Test
    fun tappingCheckAsksThePlatformOnce() {
        var asked = 0
        compose.setContent {
            MizanTheme { WelcomeContent(state = WelcomeState("http://10.0.2.2:8080"), onCheck = { asked++ }) }
        }

        compose.onNodeWithText("Check the platform").performClick()

        assertEquals(1, asked)
    }

    @Test
    fun whileCheckingTheButtonCannotBeTappedAgain() {
        compose.setContent {
            MizanTheme {
                WelcomeContent(state = WelcomeState("http://10.0.2.2:8080", checking = true), onCheck = {})
            }
        }

        compose.onNodeWithText("Checking…").assertIsNotEnabled()
    }

    @Test
    fun anUnreachablePlatformIsToldApartFromOneThatIsDown() {
        compose.setContent {
            MizanTheme {
                WelcomeContent(
                    state = WelcomeState(
                        "http://10.0.2.2:8080",
                        health = PlatformHealth.Unreachable("ConnectException: failed to connect"),
                    ),
                    onCheck = {},
                )
            }
        }

        compose.onNodeWithTag("health").assertTextContains("Nothing answered", substring = true)
    }
}
