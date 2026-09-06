package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.Idempotent;
import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.EndpointResponse;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.RegisterEndpointRequest;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.SecretResponse;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.UpdateEndpointRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a merchant wants to be told, and the secret that proves it was us.
 *
 * <p>The signing scheme is documented on the register operation in enough detail to verify a
 * delivery without asking anybody, which is the point of documenting it: a merchant who cannot
 * check the signature will not check the signature.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/webhook-endpoints",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Webhooks", description = "Where a merchant is told what happened, without polling")
public class WebhookEndpointController {

    private final WebhookEndpointService endpoints;

    public WebhookEndpointController(WebhookEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @PostMapping
    @RequiresPermission(Permission.WEBHOOK_MANAGE)
    @Idempotent
    @Operation(
            summary = "Register an endpoint",
            description =
                    """
                    Returns the signing secret, once. It is stored encrypted and will not be \
                    shown again; if it is lost, rotate it.

                    ## Verifying a delivery

                    Every delivery carries three headers:

                    - `X-Mizan-Signature` — `sha256=` followed by the lowercase hex HMAC
                    - `X-Mizan-Timestamp` — when it was signed, as Unix seconds
                    - `X-Mizan-Delivery` — the delivery's id, the same on every retry

                    Compute `HMAC-SHA256(secret, timestamp + "." + body)` over the raw request \
                    body, exactly as received and before any parsing, and compare it to the \
                    signature with a constant-time comparison. Reject anything whose timestamp \
                    is more than five minutes from your clock, or a captured delivery can be \
                    replayed at you forever.

                    The timestamp is inside the signed string on purpose: signing only the \
                    body would let somebody who recorded one delivery send it again unchanged, \
                    and the signature would still be valid.

                    ## What is refused

                    The URL must be https, and must resolve to an address on the public \
                    internet. An endpoint pointing inside this platform's own network would \
                    make this a way to have Mizan send requests to Mizan on your behalf.""")
    @ApiResponse(responseCode = "201", description = "The endpoint, and its secret, once")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "This merchant already registered that URL")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description =
                    "The URL is not https, is not on the public internet, or names an event "
                            + "type this platform does not publish")
    public ResponseEntity<SecretResponse> register(
            @PathVariable UUID merchantId, @Valid @RequestBody RegisterEndpointRequest request) {

        SecretResponse registered = endpoints.register(merchantId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/webhook-endpoints/"
                                + registered.endpoint().id()))
                .body(registered);
    }

    @GetMapping
    @RequiresPermission(Permission.WEBHOOK_READ)
    @Operation(summary = "List a merchant's endpoints", description = "Most recent first.")
    @ApiResponse(responseCode = "200", description = "The merchant's endpoints")
    public List<EndpointResponse> list(@PathVariable UUID merchantId) {
        return endpoints.list(merchantId);
    }

    @GetMapping("/{endpointId}")
    @RequiresPermission(Permission.WEBHOOK_READ)
    @Operation(summary = "Read an endpoint")
    @ApiResponse(responseCode = "200", description = "The endpoint")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no endpoint with that id")
    public EndpointResponse find(
            @PathVariable UUID merchantId, @PathVariable UUID endpointId) {

        return endpoints.find(merchantId, endpointId);
    }

    @PostMapping("/{endpointId}/secret")
    @RequiresPermission(Permission.WEBHOOK_MANAGE)
    @NotIdempotent(
            because = "rotating twice is two different secrets, and answering the second call "
                    + "with the first one's would hand back a secret that no longer signs "
                    + "anything.")
    @Operation(
            summary = "Rotate the signing secret",
            description =
                    """
                    Returns the new secret, once. Rotation takes effect immediately: every \
                    delivery after this call is signed with the new secret, and a receiver \
                    still checking the old one will reject them.

                    That is deliberate. Accepting both for a while would be a rotation that \
                    never finishes and an old secret that is never actually revoked. Deploy \
                    the new secret first, or disable the endpoint while you do.""")
    @ApiResponse(responseCode = "200", description = "The endpoint, and its new secret, once")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no endpoint with that id")
    public SecretResponse rotate(
            @PathVariable UUID merchantId, @PathVariable UUID endpointId) {

        return endpoints.rotate(merchantId, endpointId);
    }

    @PatchMapping("/{endpointId}")
    @RequiresPermission(Permission.WEBHOOK_MANAGE)
    @NotIdempotent(
            because = "the request says what to change rather than what the endpoint should "
                    + "become, so repeating it is the same change made again and lands in the "
                    + "same place.")
    @Operation(
            summary = "Change what an endpoint receives, or stop it receiving",
            description =
                    "Disabling keeps the endpoint and its delivery history. Deleting does not.")
    @ApiResponse(responseCode = "200", description = "The endpoint as it now stands")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no endpoint with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "It names an event type this platform does not publish")
    public EndpointResponse update(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody UpdateEndpointRequest request) {

        return endpoints.update(merchantId, endpointId, request);
    }

    @DeleteMapping("/{endpointId}")
    @RequiresPermission(Permission.WEBHOOK_MANAGE)
    @NotIdempotent(
            because = "deleting something that is already gone is answered as not found, which "
                    + "is the honest answer and not one worth replaying from a record.")
    @Operation(
            summary = "Remove an endpoint",
            description =
                    "Its delivery history goes with it. To stop deliveries and keep the "
                            + "history, disable it instead.")
    @ApiResponse(responseCode = "204", description = "It is gone")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no endpoint with that id")
    public ResponseEntity<Void> delete(
            @PathVariable UUID merchantId, @PathVariable UUID endpointId) {

        endpoints.delete(merchantId, endpointId);
        return ResponseEntity.noContent().build();
    }
}
