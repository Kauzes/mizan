package dev.kauzes.mizan.merchant

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.kauzes.mizan.merchant.ui.welcome.WelcomeScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Welcome)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Welcome> {
                WelcomeScreen()
            }
        },
    )
}
