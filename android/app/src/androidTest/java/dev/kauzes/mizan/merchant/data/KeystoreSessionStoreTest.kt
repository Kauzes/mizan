package dev.kauzes.mizan.merchant.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kauzes.mizan.merchant.domain.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** On a device, because the Android Keystore exists only on one. */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreSessionStore(context)

    private val session = Session(
        accessToken = "eyJ.an-access-token-that-must-not-be-readable.sig",
        refreshToken = "a-refresh-token-that-must-not-be-readable",
        accessExpiresAt = 1_900_000_000_000,
        refreshExpiresAt = 1_902_000_000_000,
        merchantId = "0f5a3b2c-1d4e-4f6a-8b9c-0d1e2f3a4b5c",
        userId = "9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b",
    )

    @Before
    @After
    fun forget() {
        store.clear()
    }

    @Test
    fun whatIsSavedIsWhatIsLoaded() {
        store.save(session)

        assertEquals(session, KeystoreSessionStore(context).load())
    }

    @Test
    fun theTokensAreNotReadableInThePreferencesFile() {
        store.save(session)

        val onDisk = context.getSharedPreferences("session", Context.MODE_PRIVATE).all.values.joinToString(" ")
        assertFalse("the refresh token is not stored as text", onDisk.contains("a-refresh-token-that-must-not-be-readable"))
        assertFalse("nor the access token", onDisk.contains("an-access-token-that-must-not-be-readable"))
        assertFalse("nor the merchant", onDisk.contains(session.merchantId))
    }

    @Test
    fun aStoredSessionThatCannotBeDecryptedIsNoSession() {
        store.save(session)
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .putString("ciphertext", "bm90IHdoYXQgd2FzIHdyaXR0ZW4=")
            .commit()

        assertNull(store.load())
        assertNull("and it is cleared rather than failing again next launch", store.load())
    }

    @Test
    fun clearingForgetsIt() {
        store.save(session)
        store.clear()

        assertNull(store.load())
    }
}
