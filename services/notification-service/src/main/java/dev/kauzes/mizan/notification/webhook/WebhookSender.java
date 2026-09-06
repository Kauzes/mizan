package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.crypto.SecretCipher;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Making one delivery, and saying honestly how it went.
 *
 * <p>Every call has a timeout, and the timeout is the reason a slow endpoint cannot hold a
 * worker for longer than a few seconds. Without one, "cannot delay anybody else" would depend
 * on every merchant's server behaving, which is precisely the thing this platform does not
 * control.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    /** How a delivery went, whatever happened. */
    public record Outcome(boolean delivered, Integer statusCode, long tookMillis, String error) {
    }

    private final RestClient http;
    private final SecretCipher cipher;
    private final WebhookDestinations destinations;

    public WebhookSender(
            RestClient.Builder builder,
            SecretCipher cipher,
            WebhookDestinations destinations,
            @Value("${mizan.webhooks.timeout:10s}") Duration timeout) {

        this.cipher = cipher;
        this.destinations = destinations;
        this.http = builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();
    }

    public Outcome send(WebhookDeliveries.Due delivery) {
        // Checked again, here, at the moment of the call. A merchant registered a hostname and
        // DNS answers to whoever controls it: a name that pointed at their server yesterday
        // can point at ours today, and a check that only ran at registration is one an
        // attacker waits out.
        var refusal = destinations.check(delivery.url());
        if (refusal.isPresent()) {
            log.warn(
                    "refusing to deliver {} to {}: {}",
                    delivery.id(),
                    delivery.url(),
                    refusal.get().because());
            return new Outcome(false, null, 0, "refused to call this URL: "
                    + refusal.get().because());
        }

        String secret;
        try {
            secret = cipher.decrypt(delivery.encryptedSecret(), delivery.endpointId().toString());
        } catch (RuntimeException unopenable) {
            // The row was edited, or the encryption key changed. Retrying will not help and
            // saying so is more use than a hundred identical failures.
            return new Outcome(false, null, 0, "this endpoint's signing secret cannot be read");
        }

        Instant signedAt = Instant.now();
        String signature = WebhookSignature.of(secret, signedAt, delivery.body());

        long startedAt = System.nanoTime();
        try {
            int status = http.post()
                    .uri(delivery.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.SIGNATURE_HEADER, signature)
                    .header(
                            WebhookSignature.TIMESTAMP_HEADER,
                            String.valueOf(signedAt.getEpochSecond()))
                    .header(WebhookSignature.DELIVERY_HEADER, delivery.id().toString())
                    .header(WebhookSignature.EVENT_TYPE_HEADER, delivery.eventType())
                    .body(delivery.body())
                    // exchange rather than retrieve, because a 500 from a merchant's server is
                    // an outcome to record rather than an exception to throw. Their endpoint
                    // being broken is not this platform's error.
                    .exchange((request, response) -> response.getStatusCode().value(), false);

            long took = millisSince(startedAt);
            boolean accepted = status >= 200 && status < 300;

            if (accepted) {
                log.debug("delivered {} to {} in {}ms", delivery.id(), delivery.url(), took);
            } else {
                log.info(
                        "{} answered {} for delivery {}", delivery.url(), status, delivery.id());
            }
            return new Outcome(accepted, status, took, accepted ? null : "answered " + status);

        } catch (Exception noAnswer) {
            long took = millisSince(startedAt);
            log.info(
                    "could not deliver {} to {} after {}ms: {}",
                    delivery.id(),
                    delivery.url(),
                    took,
                    noAnswer.getMessage());
            // A null status code rather than a made-up one. "Nothing answered" and "answered
            // 500" are different facts, and a merchant reading their delivery history should
            // be able to tell which they had.
            return new Outcome(false, null, took, describe(noAnswer));
        }
    }

    private static long millisSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage() == null ? "" : ": " + failure.getMessage();
        return failure.getClass().getSimpleName() + message;
    }
}
