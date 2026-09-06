package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The cipher that opens webhook signing secrets.
 *
 * <p>Its own key, not the one that opens API key secrets. They are both merchant credentials
 * and they are held by different systems for different purposes: a compromise of one should
 * not be a compromise of the other, and they are rotated on different days by different
 * people.
 */
@Configuration
public class WebhookSecrets {

    @Bean
    SecretCipher webhookCipher(
            @Value("${mizan.webhooks.encryption-key:}") String configured) {

        return new SecretCipher(configured, "merchant webhook signing secret");
    }
}
