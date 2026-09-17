package dev.kauzes.mizan.merchant

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Where a merchant with no session starts. */
@Serializable data object SignIn : NavKey

/** Which platform the app talks to, and whether that platform is answering. Reached from sign in. */
@Serializable data object Welcome : NavKey

/** Where a signed in merchant starts. */
@Serializable data object Home : NavKey

/** Taking a payment, and finishing one that was interrupted. */
@Serializable data object TakePayment : NavKey

/** The merchant's payments, kept up to date while this screen is open. */
@Serializable data object Payments : NavKey
