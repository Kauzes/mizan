package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this platform sent a merchant, across all of their endpoints.
 *
 * <p>The endpoint-scoped list answers "is this endpoint working". This one answers the other
 * question a merchant asks, which is "did you tell me about *this payment*" — and until there
 * was a way to ask it, the only answer was to list an endpoint's deliveries and read.
 *
 * <p>Each row names the endpoint it went to, so the attempts behind it are read through the
 * endpoint's own route rather than through a second copy of it here. Two views answering the
 * same question differently is one view too many.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/webhook-deliveries",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Webhooks", description = "Where a merchant is told what happened, without polling")
public class MerchantDeliveryController {

    private final WebhookDeliveries deliveries;

    public MerchantDeliveryController(WebhookDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping
    @RequiresPermission(Permission.WEBHOOK_READ)
    @Operation(
            summary = "What was sent to this merchant",
            description =
                    """
                    Most recent first, across every endpoint. Narrowed to one payment with \
                    `paymentId`, which is what a merchant asking "did you tell me about this \
                    one" wants.

                    Each row names the endpoint it went to; the attempts behind a delivery are \
                    read through that endpoint's own route.""")
    @ApiResponse(responseCode = "200", description = "The deliveries")
    public List<Map<String, Object>> list(
            @PathVariable UUID merchantId,
            @Parameter(description = "Only what was sent about this payment")
                    @RequestParam(required = false)
                    UUID paymentId,
            @RequestParam(defaultValue = "100") int limit) {

        return deliveries.forMerchant(merchantId, paymentId, Math.min(Math.max(limit, 1), 500));
    }
}
