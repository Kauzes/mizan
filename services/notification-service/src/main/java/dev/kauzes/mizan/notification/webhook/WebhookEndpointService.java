package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.crypto.SecretCipher;
import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.EndpointResponse;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.RegisterEndpointRequest;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.SecretResponse;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.UpdateEndpointRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registering where a merchant wants to be told, and keeping the secret that proves it. */
@Service
public class WebhookEndpointService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEndpointService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 32 bytes of secret.
     *
     * <p>The same length as the HMAC-SHA256 output it will produce. Longer buys nothing,
     * because HMAC folds a longer key down to the block size; shorter is the only mistake
     * available here.
     */
    private static final int SECRET_BYTES = 32;

    /**
     * What a merchant may subscribe to.
     *
     * <p>A closed set, checked here, so that a typo is refused at registration rather than
     * being an endpoint that silently receives nothing forever. The types are the payment
     * service's, and they arrive on the envelope of every event this service consumes.
     */
    private static final Set<String> KNOWN_TYPES = Set.of(
            "payment.authorized",
            "payment.declined",
            "payment.captured",
            "payment.voided",
            "payment.refunded");

    private final WebhookEndpointRepository endpoints;
    private final SecretCipher cipher;
    private final WebhookDestinations destinations;

    public WebhookEndpointService(
            WebhookEndpointRepository endpoints,
            SecretCipher cipher,
            WebhookDestinations destinations) {

        this.endpoints = endpoints;
        this.cipher = cipher;
        this.destinations = destinations;
    }

    /**
     * Registers an endpoint and hands back its secret, once.
     *
     * <p>The secret is generated here, never taken from the caller. A merchant-chosen secret is
     * a merchant-chosen password, and the failure mode is the one every password has.
     */
    @Transactional
    public SecretResponse register(UUID merchantId, RegisterEndpointRequest request) {
        String url = request.url().trim();
        requireSafe(url);

        requireKnownTypes(request.eventTypes());

        WebhookEndpoint endpoint = new WebhookEndpoint(
                merchantId, url, request.description(), request.eventTypes());

        // Bound to the endpoint's id, which exists before the row does because the entity
        // assigns its own. A database-generated id would only arrive at insert, which would
        // mean writing the row once without its secret — and the column refuses that.
        String secret = freshSecret();
        endpoint.secretIs(cipher.encrypt(secret, endpoint.id().toString()));

        try {
            endpoints.saveAndFlush(endpoint);
        } catch (DataIntegrityViolationException violated) {
            // Only the one constraint. Reporting every integrity violation as a duplicate URL
            // is how a genuine bug arrives dressed as a caller's mistake, and it hid one here
            // for an afternoon.
            if (isDuplicateUrl(violated)) {
                throw new ConflictException(
                        "This merchant already has an endpoint registered at that URL.");
            }
            throw violated;
        }

        log.info(
                "merchant {} registered webhook endpoint {} for {}",
                merchantId,
                endpoint.id(),
                endpoint.eventTypes());
        return SecretResponse.of(endpoint, secret);
    }

    /**
     * Issues a new secret and forgets the old one.
     *
     * <p>Immediate rather than overlapping: from this moment every delivery is signed with the
     * new one, and a receiver still checking against the old one will reject them. That is the
     * honest behaviour and it is worth saying out loud in the API description, because the
     * alternative — accepting two secrets for a while — is a rotation that never finishes and
     * an old secret that is never actually revoked.
     */
    @Transactional
    public SecretResponse rotate(UUID merchantId, UUID endpointId) {
        WebhookEndpoint endpoint = mine(merchantId, endpointId);
        String secret = freshSecret();
        endpoint.secretIs(cipher.encrypt(secret, endpoint.id().toString()));

        log.info("merchant {} rotated the secret for endpoint {}", merchantId, endpointId);
        return SecretResponse.of(endpoint, secret);
    }

    @Transactional
    public EndpointResponse update(
            UUID merchantId, UUID endpointId, UpdateEndpointRequest request) {

        WebhookEndpoint endpoint = mine(merchantId, endpointId);

        if (request.eventTypes() != null) {
            requireKnownTypes(request.eventTypes());
            endpoint.wants(request.eventTypes());
        }
        if (request.enabled() != null) {
            endpoint.enabled(request.enabled());
        }
        return EndpointResponse.of(endpoint);
    }

    @Transactional
    public void delete(UUID merchantId, UUID endpointId) {
        endpoints.delete(mine(merchantId, endpointId));
        log.info("merchant {} removed webhook endpoint {}", merchantId, endpointId);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> list(UUID merchantId) {
        return endpoints.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(EndpointResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public EndpointResponse find(UUID merchantId, UUID endpointId) {
        return EndpointResponse.of(mine(merchantId, endpointId));
    }

    /**
     * The secret this endpoint is signed with, for the deliverer.
     *
     * <p>Not reachable through any endpoint, and not on any response type. The only caller is
     * the thing that signs a delivery.
     */
    public String signingSecretOf(WebhookEndpoint endpoint) {
        return cipher.decrypt(endpoint.encryptedSecret(), endpoint.id().toString());
    }

    /** Whether this violation is the unique index on (merchant, url) and not something else. */
    private static boolean isDuplicateUrl(DataIntegrityViolationException violated) {
        String detail = String.valueOf(violated.getMostSpecificCause().getMessage());
        return detail.contains("webhook_endpoint_url_once");
    }

    private WebhookEndpoint mine(UUID merchantId, UUID endpointId) {
        return endpoints
                .findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> new NotFoundException("No webhook endpoint with that id."));
    }

    /**
     * Refuses a URL this platform should not be talked into calling.
     *
     * <p>Checked here, and again at delivery time, because DNS can change its mind in between
     * and a check that only ran at registration is a check an attacker waits out.
     */
    private void requireSafe(String url) {
        destinations
                .check(url)
                .ifPresent(refusal -> {
                    throw new UnprocessableException(refusal.because());
                });
    }

    private static void requireKnownTypes(Set<String> eventTypes) {
        List<String> unknown = eventTypes.stream()
                .filter(type -> !KNOWN_TYPES.contains(type))
                .sorted()
                .toList();

        if (!unknown.isEmpty()) {
            throw new UnprocessableException(
                    "This platform does not publish "
                            + String.join(", ", unknown)
                            + ". It publishes "
                            + KNOWN_TYPES.stream().sorted().toList()
                            + ".");
        }
    }

    private static String freshSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return "whsec_" + ENCODER.encodeToString(bytes);
    }
}
