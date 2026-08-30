package dev.kauzes.mizan.identity.token;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The key that signs access tokens.
 *
 * <p>Only the private key is configured. The public half is derived from it, because an RSA
 * private key already contains the modulus and the public exponent, and a second property
 * would be a second thing that can be set to the wrong value.
 *
 * <p>With nothing configured, a key is generated at startup. That is deliberately unsuitable
 * for a deployment — tokens do not survive a restart and a second instance cannot verify the
 * first's — and the service says so at warn level, which is how a default stays obviously
 * local rather than quietly becoming the key everything runs on.
 */
@Configuration
public class SigningKeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfiguration.class);

    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    @Bean
    public RSAKey signingKey(TokenProperties properties) {
        RSAKey key = properties.hasConfiguredKey()
                ? fromPem(properties.privateKey())
                : generated();

        log.info("access tokens are signed with key {}", key.getKeyID());
        return key;
    }

    private static RSAKey fromPem(String pem) {
        String base64 = pem.replace(PEM_HEADER, "")
                .replace(PEM_FOOTER, "")
                .replaceAll("\\s", "");
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey)
                    rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return identified(publicKey, privateKey);
        } catch (Exception malformed) {
            // Starting with a key nobody can verify against is worse than not starting.
            throw new IllegalStateException(
                    "mizan.security.jwt.private-key is not a PKCS#8 PEM RSA private key",
                    malformed);
        }
    }

    private static RSAKey generated() {
        log.warn("no signing key configured, so one was generated for this process. "
                + "Tokens will not survive a restart and no other instance can verify them. "
                + "Set mizan.security.jwt.private-key anywhere that is not a developer's laptop.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return identified((RSAPublicKey) pair.getPublic(), pair.getPrivate());
        } catch (Exception impossible) {
            throw new IllegalStateException("RSA is not available in this JVM", impossible);
        }
    }

    /**
     * The key id is the key's own thumbprint, so it is stable across restarts for a
     * configured key and a verifier can tell two keys apart during a rotation.
     */
    private static RSAKey identified(RSAPublicKey publicKey, java.security.PrivateKey privateKey) {
        try {
            RSAKey withoutId = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            return new RSAKey.Builder(withoutId)
                    .keyID(withoutId.computeThumbprint().toString())
                    .build();
        } catch (Exception impossible) {
            throw new IllegalStateException("could not compute a key id", impossible);
        }
    }
}
