package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.Idempotent;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.payment.PaymentRequests.AuthorizeRequest;
import dev.kauzes.mizan.payment.PaymentRequests.CreatePaymentRequest;
import dev.kauzes.mizan.payment.PaymentRequests.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A merchant's payments.
 *
 * <p>Only creating and reading here. Authorizing, capturing and voiding are the stories after
 * this one, and they will move a payment through the states this one wrote down.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/payments",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Payments", description = "Taking money, from intent to captured")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    @RequiresPermission(Permission.PAYMENT_READ)
    @Operation(summary = "List a merchant's payments", description = "Most recent first.")
    @ApiResponse(responseCode = "200", description = "The merchant's payments")
    public List<PaymentResponse> list(@PathVariable UUID merchantId) {
        return payments.list(merchantId);
    }

    @PostMapping
    @RequiresPermission(Permission.PAYMENT_WRITE)
    @Idempotent
    @Operation(
            summary = "Create a payment intent",
            description =
                    """
                    Records that a payment is about to be attempted. Nobody is contacted and \
                    no money moves: this is the thing an authorization is later attached to.

                    The reference is the merchant's own name for the payment and is unique \
                    within the merchant, so a caller who never received this response can \
                    find the payment again rather than creating a second one.""")
    @ApiResponse(responseCode = "201", description = "The payment, in its first state")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "This merchant already has a payment with that reference")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "Those three letters do not name a currency")
    public ResponseEntity<PaymentResponse> create(
            @PathVariable UUID merchantId, @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResponse created = payments.create(merchantId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/payments/" + created.id()))
                .body(created);
    }

    @PostMapping("/{paymentId}/authorize")
    @RequiresPermission(Permission.PAYMENT_WRITE)
    @Idempotent
    @Operation(
            summary = "Authorize a payment",
            description =
                    """
                    Asks the acquirer to reserve the money. An approval moves the payment to                     AUTHORIZED and a refusal to DECLINED, keeping the reason the acquirer                     gave.

                    Nothing is posted to the books. An authorization is a promise that the                     money is there rather than a movement of it, and the ledger records                     movements. Capturing is what moves it.

                    Only the last four digits of the card are kept.""")
    @ApiResponse(responseCode = "200", description = "The payment, authorized or declined")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no payment with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "This payment is not in a state that can be authorized")
    @ApiResponse(
            responseCode = "504",
            ref = "#/components/responses/UPSTREAM_TIMEOUT",
            description =
                    "The acquirer did not answer in time. Whether the payment was authorized "
                            + "is not yet known, and is not assumed")
    public PaymentResponse authorize(
            @PathVariable UUID merchantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody AuthorizeRequest request) {

        return payments.authorize(merchantId, paymentId, request);
    }

    @GetMapping("/{paymentId}")
    @RequiresPermission(Permission.PAYMENT_READ)
    @Operation(
            summary = "Read a payment",
            description =
                    "Includes every step it has taken, oldest first, and where it may go next.")
    @ApiResponse(responseCode = "200", description = "The payment")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no payment with that id")
    public PaymentResponse find(@PathVariable UUID merchantId, @PathVariable UUID paymentId) {
        return payments.find(merchantId, paymentId);
    }
}
