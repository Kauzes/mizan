package dev.kauzes.mizan.identity.apikey;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts an API key secret for storage, and decrypts it to verify a signature.
 *
 * <p>Encryption rather than hashing, because HMAC is symmetric: the side checking a signature
 * has to hold the same secret that made it, and a hash holds nothing. What this buys is that
 * the database alone is not enough — the key that opens these values is configuration, and
 * lives wherever the deployment keeps secrets rather than in the rows themselves.
 *
 * <p>AES-GCM, with a fresh nonce per value, stored in front of the ciphertext. GCM
 * authenticates as well as encrypts, so a row edited in the database fails to decrypt rather
 * than decrypting to something else.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final SecretKey key;

    public SecretCipher(@Value("${mizan.security.api-keys.encryption-key:}") String configured) {
        this.key = configured == null || configured.isBlank() ? generated() : parsed(configured);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] stored = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, stored, 0, nonce.length);
            System.arraycopy(ciphertext, 0, stored, nonce.length, ciphertext.length);
            return ENCODER.encodeToString(stored);
        } catch (Exception impossible) {
            throw new IllegalStateException("could not encrypt an API key secret", impossible);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] bytes = DECODER.decode(stored);
            byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, NONCE_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(bytes, NONCE_BYTES, bytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception unopenable) {
            // Either the row was tampered with or the encryption key changed. Both mean this
            // key cannot be used, and neither is the caller's fault to explain.
            throw new IllegalStateException("could not decrypt an API key secret", unopenable);
        }
    }

    private static SecretKey parsed(String configured) {
        byte[] material = DECODER.decode(configured.trim());
        if (material.length != 32) {
            throw new IllegalStateException(
                    "mizan.security.api-keys.encryption-key must be 32 bytes of base64, was "
                            + material.length);
        }
        return new SecretKeySpec(material, "AES");
    }

    private static SecretKey generated() {
        log.warn("no API key encryption key configured, so one was generated for this process. "
                + "Keys issued now will stop verifying when this service restarts. Set "
                + "mizan.security.api-keys.encryption-key anywhere that is not a laptop.");
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (Exception impossible) {
            throw new IllegalStateException("AES is not available in this JVM", impossible);
        }
    }
}
