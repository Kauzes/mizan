package dev.kauzes.mizan.merchant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a card waits, and only while it has to.
 *
 * A payment taken with no signal cannot be authorized until there is signal, and authorizing needs the
 * card. So for that one case the card is kept — encrypted, for one payment, with an expiry — and forgotten
 * the moment the authorization is answered. Nothing else in the app stores a card (ADR 0059, ADR 0060).
 */
interface CardVault {
    /** Keeps this payment's card until [until]. Replaces anything kept for the same payment. */
    suspend fun keep(attemptId: String, card: String, until: Long)

    suspend fun read(attemptId: String): String?

    suspend fun forget(attemptId: String)

    /** Forgets every card whose time is up. Called before a sync and when the app opens. */
    suspend fun forgetExpired(now: Long)
}

/** For a taker that never queues: nothing is kept, so nothing can be read. */
object NoCardKept : CardVault {
    override suspend fun keep(attemptId: String, card: String, until: Long) = Unit
    override suspend fun read(attemptId: String): String? = null
    override suspend fun forget(attemptId: String) = Unit
    override suspend fun forgetExpired(now: Long) = Unit
}

/**
 * Cards for queued payments, encrypted with a key of their own that never leaves the Android Keystore.
 *
 * A separate key from the session's: they are wiped at different moments and for different reasons, and
 * one alias for both would mean signing out could not clear the session without also stranding a queued
 * payment's card. Same shape otherwise — AES-256-GCM, a fresh IV per write (KeystoreSessionStore).
 *
 * A card that cannot be decrypted is no card: it is cleared and the merchant is asked for it again, which
 * is the same answer as a card that expired.
 */
class KeystoreCardVault(context: Context) : CardVault {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun keep(attemptId: String, card: String, until: Long) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        preferences.edit()
            .putString("$attemptId.$IV", encode(cipher.iv))
            .putString("$attemptId.$CIPHERTEXT", encode(cipher.doFinal(card.toByteArray(Charsets.UTF_8))))
            .putLong("$attemptId.$UNTIL", until)
            .apply()
    }

    override suspend fun read(attemptId: String): String? = withContext(Dispatchers.IO) {
        val iv = preferences.getString("$attemptId.$IV", null) ?: return@withContext null
        val sealed = preferences.getString("$attemptId.$CIPHERTEXT", null) ?: return@withContext null
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(sealed)), Charsets.UTF_8)
        }.getOrElse {
            forget(attemptId)
            null
        }
    }

    override suspend fun forget(attemptId: String) = withContext(Dispatchers.IO) {
        preferences.edit()
            .remove("$attemptId.$IV")
            .remove("$attemptId.$CIPHERTEXT")
            .remove("$attemptId.$UNTIL")
            .apply()
    }

    override suspend fun forgetExpired(now: Long) = withContext(Dispatchers.IO) {
        preferences.all.keys
            .filter { it.endsWith(".$UNTIL") }
            .filter { preferences.getLong(it, 0) <= now }
            .forEach { forget(it.removeSuffix(".$UNTIL")) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mizan-queued-card"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FILE = "queued-cards"
        const val IV = "iv"
        const val CIPHERTEXT = "ciphertext"
        const val UNTIL = "until"

        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
