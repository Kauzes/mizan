package dev.kauzes.mizan.identity.apikey;

import dev.kauzes.mizan.common.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The cipher that opens this service's API key secrets.
 *
 * <p>Its own key, not shared with anything else that encrypts secrets on this platform. The
 * key that opens a merchant's signing secrets and the key that opens their webhook secrets are
 * different keys on purpose: a compromise of one should not be a compromise of the other, and
 * they are rotated by different people at different times.
 */
@Configuration
public class ApiKeySecrets {

    @Bean
    SecretCipher apiKeyCipher(
            @Value("${mizan.security.api-keys.encryption-key:}") String configured) {

        return new SecretCipher(configured, "merchant API key secret");
    }
}
