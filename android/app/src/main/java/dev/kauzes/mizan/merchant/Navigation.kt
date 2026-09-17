package dev.kauzes.mizan.merchant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.kauzes.mizan.merchant.ui.home.HomeScreen
import dev.kauzes.mizan.merchant.ui.payment.TakePaymentScreen
import dev.kauzes.mizan.merchant.ui.payments.PaymentsScreen
import dev.kauzes.mizan.merchant.ui.reviews.ReviewsScreen
import dev.kauzes.mizan.merchant.ui.signin.SignInScreen
import dev.kauzes.mizan.merchant.ui.welcome.WelcomeScreen

@Composable
fun MainNavigation() {
    val sessions = (LocalContext.current.applicationContext as MizanApp).sessions
    val session by sessions.current.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(if (sessions.current.value != null) Home else SignIn)

    // However a session ends (signing out, or the platform refusing to renew it) the merchant lands on
    // sign in, rather than on a screen whose every request will now fail.
    LaunchedEffect(session == null) {
        if (session == null && backStack.lastOrNull() != SignIn && backStack.lastOrNull() != Welcome) {
            backStack.clear()
            backStack.add(SignIn)
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<SignIn> {
                SignInScreen(
                    onSignedIn = {
                        backStack.clear()
                        backStack.add(Home)
                    },
                    onCheckPlatform = { backStack.add(Welcome) },
                )
            }
            entry<Welcome> {
                WelcomeScreen()
            }
            entry<Home> {
                HomeScreen(
                    onTakePayment = { backStack.add(TakePayment) },
                    onSeePayments = { backStack.add(Payments) },
                    onReview = { backStack.add(Reviews) },
                )
            }
            entry<TakePayment> {
                TakePaymentScreen()
            }
            entry<Payments> {
                PaymentsScreen()
            }
            entry<Reviews> {
                ReviewsScreen()
            }
        },
    )
}
