package dev.kauzes.mizan.merchant

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Where the app starts: which platform it talks to, and whether that platform is answering. */
@Serializable data object Welcome : NavKey
