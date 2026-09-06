package dev.kauzes.mizan.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * That the safety check still bites at delivery time.
 *
 * <p>{@link WebhookDeliveryTest} switches it off, because a test needs a merchant server this
 * JVM can actually run. This class does not, which is the point of it: without something
 * asserting the default, "we turned the check off for the tests" quietly becomes "the check is
 * off".
 *
 * <p>It also covers the case registration cannot: an endpoint whose URL was fine when it was
 * registered and points somewhere else now. DNS answers to whoever controls it.
 */
@SpringBootTest
class WebhookDestinationAtDeliveryTest extends MizanIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebhookSender sender;

    @Test
    void refusesToDeliverToAnAddressInsideThisNetwork() {
        // Straight into the table, standing in for a hostname that resolved to the merchant's
        // server at registration and resolves here now. There is no other way to arrive at
        // this state, which is exactly why the check runs twice.
        UUID endpoint = anEndpointPointingAt("https://127.0.0.1/hook");

        WebhookSender.Outcome outcome = sender.send(new WebhookDeliveries.Due(
                UUID.randomUUID(),
                UUID.randomUUID(),
                endpoint,
                "https://127.0.0.1/hook",
                "irrelevant, it never gets that far",
                "payment.captured",
                "{}",
                1));

        assertThat(outcome.delivered()).isFalse();
        assertThat(outcome.error())
                .contains("refused to call this URL")
                .contains("not an address on the public internet");
        assertThat(outcome.statusCode())
                .as("nothing was called, so there is no status code to report")
                .isNull();
    }

    @Test
    void refusesToDeliverInClearText() {
        UUID endpoint = anEndpointPointingAt("http://example.com/hook");

        WebhookSender.Outcome outcome = sender.send(new WebhookDeliveries.Due(
                UUID.randomUUID(),
                UUID.randomUUID(),
                endpoint,
                "http://example.com/hook",
                "irrelevant",
                "payment.captured",
                "{}",
                1));

        assertThat(outcome.delivered()).isFalse();
        assertThat(outcome.error()).contains("has to be https");
    }

    private UUID anEndpointPointingAt(String url) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into webhook_endpoint (id, merchant_id, url, secret, secret_rotated_at, "
                        + "enabled, created_at, updated_at) values (?, ?, ?, ?, ?, true, ?, ?)",
                id,
                UUID.randomUUID(),
                url,
                "not-a-real-ciphertext",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return id;
    }
}
