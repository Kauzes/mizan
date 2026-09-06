package dev.kauzes.mizan.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypts a shared secret for storage, and decrypts it to use.
 *
 * <p>Encryption rather than hashing, because HMAC is symmetric: the side checking a signature
 * has to hold the same secret that made it, and a hash holds nothing. What this buys is that
 * the database alone is not enough — the key that opens these values is configuration, and
 * lives wherever the deployment keeps secrets rather than in the rows themselves.
 *
 * <p>AES-GCM, with a fresh nonce per value, stored in front of the ciphertext. GCM
 * authenticates as well as encrypts, so a row edited in the database fails to decrypt rather
 * than decrypting to something else.
 *
 * <p>Each value is bound to the thing it belongs to, by authenticating that thing's id
 * alongside it. Without that, every ciphertext is interchangeable: somebody able to write to
 * the table could copy the encrypted secret from a row they legitimately hold onto another
 * merchant's row, and then sign that merchant's traffic with a secret they already know.
 * Bound, the same ciphertext under a different id simply fails to open.
 *
 * <p>Lives here rather than beside one of its users because there are now two: the API key
 * secrets a merchant's server signs with, and the webhook secrets a merchant verifies
 * deliveries with. They deliberately do not share an encryption key — a compromise of one
 * should not open the other — but there is no reason for two copies of the same algorithm.
 */
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final SecretKey key;

    /** What this cipher is for, used only to make its warnings and errors legible. */
    private final String purpose;

    /**
     * @param configured 32 bytes of base64, or blank to generate one for this process, which
     *     is only ever right on a laptop
     * @param purpose what these secrets are, and the configuration property that sets the key
     */
    public SecretCipher(String configured, String purpose) {
        this.purpose = purpose;
        this.key = configured == null || configured.isBlank() ? generated(purpose)
                : parsed(configured, purpose);
    }

    /**
     * @param boundTo what this value belongs to, authenticated but not encrypted. Decryption
     *     with a different value fails rather than returning something usable.
     */
    public String encrypt(String plaintext, String boundTo) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(boundTo.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] stored = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, stored, 0, nonce.length);
            System.arraycopy(ciphertext, 0, stored, nonce.length, ciphertext.length);
            return ENCODER.encodeToString(stored);
        } catch (Exception impossible) {
            throw new IllegalStateException("could not encrypt a " + purpose, impossible);
        }
    }

    public String decrypt(String stored, String boundTo) {
        try {
            byte[] bytes = DECODER.decode(stored);
            byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, NONCE_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(bytes, NONCE_BYTES, bytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(boundTo.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception unopenable) {
            // The row was edited, the value belongs to something else, or the encryption key
            // changed. All of them mean this secret cannot be used, and none of them is
            // something to explain to whoever is holding it.
            throw new SecretUnavailableException(purpose, unopenable);
        }
    }

    /** Raised when a stored secret cannot be opened, whatever the reason. */
    public static final class SecretUnavailableException extends RuntimeException {

        private SecretUnavailableException(String purpose, Throwable cause) {
            super("could not decrypt a " + purpose, cause);
        }
    }

    private static SecretKey parsed(String configured, String purpose) {
        byte[] material = DECODER.decode(configured.trim());
        if (material.length != 32) {
            throw new IllegalStateException(
                    "the encryption key for a " + purpose + " must be 32 bytes of base64, was "
                            + material.length);
        }
        return new SecretKeySpec(material, "AES");
    }

    private static SecretKey generated(String purpose) {
        log.warn("no encryption key configured for a {}, so one was generated for this process. "
                + "Secrets stored now will stop opening when this service restarts. Configure "
                + "one anywhere that is not a laptop.", purpose);
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (Exception impossible) {
            throw new IllegalStateException("AES is not available in this JVM", impossible);
        }
    }
}
