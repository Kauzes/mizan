package dev.kauzes.mizan.merchant.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Against the device's real Keystore, because that is the part being relied on. */
@RunWith(AndroidJUnit4::class)
class KeystoreCardVaultTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("queued-cards", Context.MODE_PRIVATE)
    private lateinit var vault: KeystoreCardVault

    private val card = "4000000000000069"
    private val hour = 60L * 60 * 1000

    @Before
    fun empty() {
        preferences.edit().clear().commit()
        vault = KeystoreCardVault(context)
    }

    @Test
    fun aKeptCardComesBackForItsOwnPaymentOnly() = runBlocking {
        vault.keep("attempt-1", card, until = hour)

        assertEquals(card, vault.read("attempt-1"))
        assertNull("another payment's card is not this one", vault.read("attempt-2"))
    }

    @Test
    fun whatIsOnDiskIsNotTheCard() = runBlocking {
        vault.keep("attempt-1", card, until = hour)

        val stored = preferences.all.entries.joinToString(" ") { "${it.key}=${it.value}" }
        assertFalse("the card is not in the file", stored.contains(card))
        assertFalse("nor is any run of twelve or more digits", Regex("\\d{12,}").containsMatchIn(stored))
    }

    @Test
    fun forgettingLeavesNothingBehind() = runBlocking {
        vault.keep("attempt-1", card, until = hour)
        vault.forget("attempt-1")

        assertNull(vault.read("attempt-1"))
        assertEquals("no keys at all for that payment", emptySet<String>(), preferences.all.keys)
    }

    @Test
    fun aCardKeptTooLongIsForgottenAndOneStillInTimeIsNot() = runBlocking {
        vault.keep("old", card, until = hour)
        vault.keep("fresh", card, until = 3 * hour)

        vault.forgetExpired(now = 2 * hour)

        assertNull(vault.read("old"))
        assertEquals(card, vault.read("fresh"))
    }

    @Test
    fun aCardThatCannotBeDecryptedIsNoCard() = runBlocking {
        vault.keep("attempt-1", card, until = hour)
        preferences.edit().putString("attempt-1.ciphertext", "bm90IHRoZSBjYXJk").commit()

        assertNull("tampered ciphertext is not read as a card", vault.read("attempt-1"))
        assertEquals("and it is cleared rather than left to fail again", emptySet<String>(), preferences.all.keys)
    }
}
