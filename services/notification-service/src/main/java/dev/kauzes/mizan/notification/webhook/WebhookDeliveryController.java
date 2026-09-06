package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What we tried to tell a merchant, and how it went.
 *
 * <p>Visible to the merchant rather than only to an operator, because the endpoint being
 * debugged is theirs. A merchant who can see that we got a 502 at 10:00 and timed out at
 * 10:00:04 can fix their server; one who can only see that "webhooks are not working" opens a
 * support ticket.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Webhooks", description = "Where a merchant is told what happened, without polling")
public class WebhookDeliveryController {

    private final WebhookDeliveries deliveries;
    private final WebhookEndpointRepository endpoints;

    public WebhookDeliveryController(
            WebhookDeliveries deliveries, WebhookEndpointRepository endpoints) {

        this.deliveries = deliveries;
        this.endpoints = endpoints;
    }

    @GetMapping
    @RequiresPermission(Permission.WEBHOOK_READ)
    @Operation(
            summary = "What has been sent to this endpoint",
            description =
                    "Most recent first, with the status, the last response code and when it "
                            + "was delivered.")
    @ApiResponse(responseCode = "200", description = "The deliveries")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no endpoint with that id")
    public List<Map<String, Object>> list(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @RequestParam(defaultValue = "100") int limit) {

        requireTheirs(merchantId, endpointId);
        return deliveries.forEndpoint(merchantId, endpointId, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/{deliveryId}/attempts")
    @RequiresPermission(Permission.WEBHOOK_READ)
    @Operation(
            summary = "Every attempt at one delivery",
            description =
                    """
                    Each with its response code, how long it took, and what went wrong. \
                    Keeping only the last attempt would answer "is it working now", which is \
                    the one question a merchant can already answer themselves.""")
    @ApiResponse(responseCode = "200", description = "The attempts, oldest first")
    public List<Map<String, Object>> attempts(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @PathVariable UUID deliveryId) {

        requireTheirs(merchantId, endpointId);
        return deliveries.attemptsOf(merchantId, deliveryId);
    }

    @PostMapping("/{deliveryId}/redeliver")
    @RequiresPermission(Permission.WEBHOOK_MANAGE)
    @NotIdempotent(
            because = "asking twice is asking for it to be sent twice, which is a thing a "
                    + "merchant may legitimately want and not something to answer from a "
                    + "record of the first request.")
    @Operation(
            summary = "Send one again",
            description =
                    """
                    Queues the delivery again from attempt zero, with the same body and the \
                    same delivery id it had the first time. A merchant who received it already \
                    will recognise the repeat.""")
    @ApiResponse(responseCode = "202", description = "It is queued")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no such delivery")
    public Map<String, Object> redeliver(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @PathVariable UUID deliveryId) {

        requireTheirs(merchantId, endpointId);
        deliveries
                .find(merchantId, deliveryId)
                .orElseThrow(() -> new NotFoundException("No delivery with that id."));

        deliveries.redeliver(deliveryId);
        return Map.of("redelivered", deliveryId, "status", "PENDING");
    }

    /**
     * Another merchant's endpoint is not found rather than found and then hidden.
     *
     * <p>Checked on the endpoint rather than only on the delivery, so that guessing a delivery
     * id under an endpoint you do own tells you nothing about deliveries you do not.
     */
    private void requireTheirs(UUID merchantId, UUID endpointId) {
        endpoints
                .findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> new NotFoundException("No webhook endpoint with that id."));
    }
}
