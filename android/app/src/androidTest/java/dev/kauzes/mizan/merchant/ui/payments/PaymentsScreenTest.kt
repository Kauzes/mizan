package dev.kauzes.mizan.merchant.ui.payments

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kauzes.mizan.merchant.data.FeedState
import dev.kauzes.mizan.merchant.data.RemotePayment
import dev.kauzes.mizan.merchant.theme.MizanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun payment(id: String, status: String) =
        RemotePayment(id, status, null, "0000", amount = 12550, currency = "TRY")

    @Test
    fun aHeldPaymentSaysItIsWaitingForAPerson() {
        compose.setContent {
            MizanTheme {
                PaymentsContent(FeedState(payments = listOf(payment("a", "HELD_FOR_REVIEW")), loading = false))
            }
        }

        compose.onNodeWithTag("payment-a").assertIsDisplayed()
        compose.onNodeWithText("125.50 TRY").assertIsDisplayed()
        compose.onNodeWithText("Held for review — waiting for a person").assertIsDisplayed()
    }

    @Test
    fun aListThatCouldNotBeRefreshedKeepsItsPaymentsAndSaysSo() {
        compose.setContent {
            MizanTheme {
                PaymentsContent(
                    FeedState(
                        payments = listOf(payment("a", "CAPTURED")),
                        loading = false,
                        problem = "Not up to date: no route to host.",
                    ),
                )
            }
        }

        compose.onNodeWithTag("payment-a").assertIsDisplayed()
        compose.onNodeWithTag("problem").assertTextContains("Not up to date", substring = true)
    }

    @Test
    fun withNothingTakenYetTheScreenSaysSoRatherThanLookingBroken() {
        compose.setContent { MizanTheme { PaymentsContent(FeedState(loading = false)) } }

        compose.onNodeWithTag("empty").assertIsDisplayed()
    }
}
