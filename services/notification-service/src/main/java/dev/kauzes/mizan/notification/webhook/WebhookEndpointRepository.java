package dev.kauzes.mizan.notification.webhook;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /**
     * Scoped to the merchant, so another merchant's endpoint is not found rather than found
     * and then hidden.
     */
    Optional<WebhookEndpoint> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<WebhookEndpoint> findByMerchantIdAndUrl(UUID merchantId, String url);

    /**
     * Who wants to hear about this, for this merchant.
     *
     * <p>The one question asked per notification. Disabled endpoints are left out here rather
     * than filtered afterwards, so an endpoint somebody switched off stops costing anything.
     */
    @Query("select e from WebhookEndpoint e join e.eventTypes t "
            + "where e.merchantId = :merchantId and e.enabled = true and t = :eventType")
    List<WebhookEndpoint> wanting(
            @Param("merchantId") UUID merchantId, @Param("eventType") String eventType);
}
