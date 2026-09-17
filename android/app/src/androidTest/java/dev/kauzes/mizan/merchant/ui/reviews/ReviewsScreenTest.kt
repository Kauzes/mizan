package dev.kauzes.mizan.merchant.ui.reviews

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kauzes.mizan.merchant.data.QueueState
import dev.kauzes.mizan.merchant.data.RemotePayment
import dev.kauzes.mizan.merchant.theme.MizanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val held = RemotePayment(
        id = "a",
        status = "HELD_FOR_REVIEW",
        declineReason = null,
        cardLastFour = "0000",
        amount = 100_000,
        currency = "TRY",
        riskVerdict = "REVIEW",
        riskReasons = "the amount is exactly 1000 of the major unit",
    )

    @Test
    fun aHeldPaymentShowsWhatItIsAndWhyItWasHeld() {
        compose.setContent {
            MizanTheme {
                ReviewsContent(QueueState(waiting = listOf(held), loading = false), RulingState(), { _, _ -> }, {}, {})
            }
        }

        compose.onNodeWithText("1000.00 TRY").assertIsDisplayed()
        compose.onNodeWithText("the amount is exactly 1000 of the major unit").assertIsDisplayed()
        compose.onNodeWithTag("release-a").assertIsDisplayed()
        compose.onNodeWithTag("refuse-a").assertIsDisplayed()
    }

    @Test
    fun nothingCanBeRuledOnWithoutAReason() {
        compose.setContent {
            MizanTheme {
                ReviewsContent(QueueState(waiting = listOf(held), loading = false), RulingState(), { _, _ -> }, {}, {})
            }
        }

        compose.onNodeWithTag("release-a").assertIsNotEnabled()
        compose.onNodeWithTag("refuse-a").assertIsNotEnabled()
    }

    @Test
    fun theReasonTypedIsReportedForThatPayment() {
        var forPayment = ""
        var typed = ""
        compose.setContent {
            MizanTheme {
                ReviewsContent(
                    QueueState(waiting = listOf(held), loading = false),
                    RulingState(),
                    { id, text -> forPayment = id; typed = text },
                    {}, {},
                )
            }
        }

        compose.onNodeWithTag("why-a").performTextInput("a known customer")

        assertEquals("a", forPayment)
        assertEquals("a known customer", typed)
    }

    @Test
    fun withAReasonApprovingSendsThatPayment() {
        var approved: RemotePayment? = null
        var declined: RemotePayment? = null
        compose.setContent {
            MizanTheme {
                ReviewsContent(
                    QueueState(waiting = listOf(held), loading = false),
                    RulingState(why = mapOf("a" to "a known customer")),
                    { _, _ -> },
                    { approved = it },
                    { declined = it },
                )
            }
        }

        compose.onNodeWithTag("release-a").assertIsEnabled().performClick()

        assertEquals("a", approved?.id)
        assertEquals("declining is a different button", null, declined)
    }

    @Test
    fun whileARulingIsInFlightNothingElseCanBeRuledOn() {
        compose.setContent {
            MizanTheme {
                ReviewsContent(
                    QueueState(waiting = listOf(held), loading = false),
                    RulingState(why = mapOf("a" to "a known customer"), busy = "a"),
                    { _, _ -> }, {}, {},
                )
            }
        }

        compose.onNodeWithTag("release-a").assertIsNotEnabled()
        compose.onNodeWithTag("refuse-a").assertIsNotEnabled()
    }

    @Test
    fun aPaymentSomebodyElseRuledOnIsShownInThePlatformsWords() {
        compose.setContent {
            MizanTheme {
                ReviewsContent(
                    QueueState(waiting = listOf(held), loading = false),
                    RulingState(refusal = "This payment was already ruled on."),
                    { _, _ -> }, {}, {},
                )
            }
        }

        compose.onNodeWithTag("refusal").assertTextContains("already ruled on", substring = true)
    }

    @Test
    fun anAccountThatMayNotRuleIsToldSoAndOfferedNothing() {
        compose.setContent {
            MizanTheme {
                ReviewsContent(QueueState(loading = false, notAllowed = true), RulingState(), { _, _ -> }, {}, {})
            }
        }

        compose.onNodeWithTag("not-allowed").assertIsDisplayed()
        compose.onNodeWithText("Nothing is waiting. Every held payment has been ruled on.").assertDoesNotExist()
    }

    @Test
    fun anEmptyQueueSaysEverythingHasBeenRuledOn() {
        compose.setContent {
            MizanTheme {
                ReviewsContent(QueueState(loading = false), RulingState(), { _, _ -> }, {}, {})
            }
        }

        compose.onNodeWithTag("empty").assertIsDisplayed()
    }
}
