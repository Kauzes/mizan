package dev.kauzes.mizan.merchant.ui.payment

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kauzes.mizan.merchant.data.Conflict
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import dev.kauzes.mizan.merchant.theme.MizanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TakePaymentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val interrupted = PaymentAttempt(
        id = "attempt-1",
        reference = "phone-attempt-1",
        amount = 12550,
        currency = "TRY",
        description = null,
        createKey = "c",
        authorizeKey = "a",
        captureKey = "p",
        paymentId = "payment-1",
        step = AttemptStep.AUTHORIZING,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun whileAPaymentIsBeingTakenNothingCanBeSentAgain() {
        compose.setContent {
            MizanTheme {
                TakePaymentContent(TakePaymentState(running = true), emptyList(), {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("Working…").assertIsNotEnabled()
    }

    @Test
    fun anInterruptedPaymentIsOfferedAndCanBeResumed() {
        var resumed: PaymentAttempt? = null
        compose.setContent {
            MizanTheme {
                TakePaymentContent(TakePaymentState(), listOf(interrupted), {}, {}, {}, {}, { resumed = it }, {})
            }
        }

        compose.onNodeWithText("Continue 125.50 TRY").assertIsDisplayed().performClick()
        assertEquals("attempt-1", resumed?.id)
    }

    @Test
    fun withNoSignalTheScreenSaysPaymentsCanStillBeTakenAndNothingCanBeSentNow() {
        compose.setContent {
            MizanTheme {
                TakePaymentContent(TakePaymentState(online = false), listOf(interrupted), {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithTag("offline").assertTextContains("sent when signal returns", substring = true)
        compose.onNodeWithTag("send-queued").assertIsNotEnabled()
        compose.onNodeWithTag("waiting").assertTextContains("Waiting to be sent (1)")
    }

    @Test
    fun aPaymentTheQueueCouldNotSendIsShownWithItsReason() {
        compose.setContent {
            MizanTheme {
                TakePaymentContent(
                    TakePaymentState(conflicts = listOf(Conflict(interrupted, "This payment needs the card again before it can be sent."))),
                    listOf(interrupted), {}, {}, {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithTag("conflict-attempt-1").assertTextContains("needs the card again", substring = true)
    }

    @Test
    fun finishingAPaymentShowsItsAmountAndAsksOnlyForTheCard() {
        compose.setContent {
            MizanTheme {
                TakePaymentContent(
                    TakePaymentState(continuing = interrupted, message = "Enter the card again to finish the payment of 125.50 TRY."),
                    emptyList(), {}, {}, {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithTag("continuing").assertTextContains("125.50 TRY")
        compose.onNodeWithText("Finish payment").assertIsDisplayed()
        compose.onNodeWithTag("result").assertTextContains("Enter the card again", substring = true)
    }
}
