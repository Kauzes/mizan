package dev.kauzes.mizan.notification.webhook;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** What a merchant sends when saying where to be told, and what they see back. */
public final class WebhookRequests {

    private WebhookRequests() {
    }

    @Schema(description = "Somewhere to be told, and what about")
    public record RegisterEndpointRequest(
            @Schema(
                            description =
                                    "Must be https, and must resolve to an address on the public "
                                            + "internet. An endpoint inside this platform's own "
                                            + "network is refused.",
                            example = "https://api.example.com/webhooks/mizan")
                    @NotBlank
                    @Size(max = 2000)
                    String url,
            @Schema(example = "Production order service") @Size(max = 200) String description,
            @Schema(
                            description = "Which events this endpoint should receive",
                            example = "[\"payment.captured\",\"payment.refunded\"]")
                    @NotEmpty(message = "an endpoint that wants nothing would never be called")
                    Set<String> eventTypes) {
    }

    @Schema(description = "What to change about an endpoint")
    public record UpdateEndpointRequest(
            @Schema(description = "Replaces what it subscribes to, if given") Set<String> eventTypes,
            @Schema(
                            description =
                                    "Stop or resume deliveries. Disabling keeps the endpoint and "
                                            + "its history; deleting does not.")
                    Boolean enabled) {
    }

    @Schema(description = "A registered endpoint")
    public record EndpointResponse(
            UUID id,
            UUID merchantId,
            String url,
            String description,
            Set<String> eventTypes,
            boolean enabled,
            @Schema(
                            description =
                                    "When the secret was last replaced. The secret itself is "
                                            + "never returned after it is issued.")
                    Instant secretRotatedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static EndpointResponse of(WebhookEndpoint endpoint) {
            return new EndpointResponse(
                    endpoint.id(),
                    endpoint.merchantId(),
                    endpoint.url(),
                    endpoint.description(),
                    endpoint.eventTypes(),
                    endpoint.isEnabled(),
                    endpoint.secretRotatedAt(),
                    endpoint.createdAt(),
                    endpoint.updatedAt());
        }
    }

    /**
     * An endpoint, with its secret, returned exactly once.
     *
     * <p>The only response on this platform that carries a secret. It exists because there is
     * no other moment at which the merchant can be given it: this platform keeps the secret
     * encrypted and will not show it again, which is the property that makes storing it
     * defensible at all.
     */
    @Schema(description = "A registered endpoint and its signing secret, shown once")
    public record SecretResponse(
            @Schema(description = "The endpoint") EndpointResponse endpoint,
            @Schema(
                            description =
                                    "Keep this. It is shown now and never again, and it is what "
                                            + "you verify a delivery's signature with.",
                            example = "whsec_Zm9vYmFyYmF6cXV1eA")
                    String secret) {

        public static SecretResponse of(WebhookEndpoint endpoint, String secret) {
            return new SecretResponse(EndpointResponse.of(endpoint), secret);
        }
    }
}
