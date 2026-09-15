package dev.kauzes.mizan.merchant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.kauzes.mizan.merchant.domain.Session
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The session, encrypted, with a key that never leaves the Android Keystore.
 *
 * Written by hand rather than with Jetpack's EncryptedSharedPreferences, which is deprecated. It is
 * little: an AES-256-GCM key generated inside the Keystore, a fresh IV per write, and the ciphertext in
 * ordinary preferences. Somebody who copies the preferences file gets bytes they cannot decrypt without
 * this device's Keystore, and a refresh token is a thirty-day credential worth that.
 *
 * If the key is gone (the app's data was cleared, or the Keystore was reset) the stored session cannot
 * be read, and it is treated as no session rather than as an error: the merchant signs in again.
 */
class KeystoreSessionStore(context: Context) : SessionStore {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Session? {
        val iv = preferences.getString(IV, null) ?: return null
        val sealed = preferences.getString(CIPHERTEXT, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decode(iv)))
            val stored = Json.decodeFromString<Stored>(String(cipher.doFinal(decode(sealed)), Charsets.UTF_8))
            stored.toSession()
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(session: Session) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = Json.encodeToString(Stored.serializer(), Stored.of(session)).toByteArray(Charsets.UTF_8)
        preferences.edit()
            .putString(IV, encode(cipher.iv))
            .putString(CIPHERTEXT, encode(cipher.doFinal(plaintext)))
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
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

    @Serializable
    private data class Stored(
        val accessToken: String,
        val refreshToken: String,
        val accessExpiresAt: Long,
        val refreshExpiresAt: Long,
        val merchantId: String,
        val userId: String,
    ) {
        fun toSession() = Session(accessToken, refreshToken, accessExpiresAt, refreshExpiresAt, merchantId, userId)

        companion object {
            fun of(session: Session) = Stored(
                session.accessToken,
                session.refreshToken,
                session.accessExpiresAt,
                session.refreshExpiresAt,
                session.merchantId,
                session.userId,
            )
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mizan-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FILE = "session"
        const val IV = "iv"
        const val CIPHERTEXT = "ciphertext"

        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
