package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.identity.Caller;
import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.payment.PaymentRequests.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue of payments this platform stopped, and what an analyst does about them.
 *
 * <p>The other half of MIZ-58. Holding a payment for review is only worth doing if somebody
 * can review it, and a hold nobody can act on is a decline with extra steps and a customer
 * waiting for nothing.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/reviews",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Reviews", description = "What this platform stopped, and who decided about it")
public class ReviewController {

    private final ReviewQueue queue;

    public ReviewController(ReviewQueue queue) {
        this.queue = queue;
    }

    @GetMapping
    @RequiresPermission(Permission.REVIEW_RULE)
    @Operation(
            summary = "What is waiting for a person",
            description =
                    """
                    Oldest first, because the oldest is the customer who has been waiting \
                    longest and because a queue worked newest-first grows a tail nobody \
                    reaches.

                    A payment leaves this list when somebody rules on it, or when it expires \
                    on its own. Nothing here has been charged: the money was never reserved.""")
    @ApiResponse(responseCode = "200", description = "The payments waiting to be ruled on")
    public List<PaymentResponse> waiting(@PathVariable UUID merchantId) {
        return queue.waiting(merchantId);
    }

    @PostMapping("/{paymentId}/release")
    @RequiresPermission(Permission.REVIEW_RULE)
    @NotIdempotent(
            because = "a payment can only be ruled on once, and a second attempt is refused "
                    + "because the payment is no longer held rather than answered from a "
                    + "record of the first.")
    @Operation(
            summary = "Release a held payment",
            description =
                    """
                    Says the scorer was wrong about this one. The payment can then be \
                    authorized, and will not be scored again: a person has overruled the \
                    scorer, and asking it a second time would let it overrule them back.

                    Deliberately does not authorize it. Taking the money stays a thing the \
                    merchant does, through the same endpoint as any other payment, so that \
                    an analyst's click never charges a customer.

                    Who ruled is taken from the access token, never from this request, and \
                    a merchant's own API key may not rule at all.""")
    @ApiResponse(responseCode = "200", description = "The payment, released")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "403",
            ref = "#/components/responses/FORBIDDEN",
            description = "An API key cannot rule on a review")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no payment with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "This payment is not waiting for anybody to rule on it")
    public PaymentResponse release(
            @PathVariable UUID merchantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody RulingRequest request,
            Caller caller) {

        return queue.release(caller, paymentId, request.why().trim());
    }

    @PostMapping("/{paymentId}/refuse")
    @RequiresPermission(Permission.REVIEW_RULE)
    @NotIdempotent(because = "the same reason as releasing one: ruled once, and then not held.")
    @Operation(
            summary = "Refuse a held payment",
            description =
                    """
                    Agrees with the scorer. Nobody was charged, and now nobody will be.

                    Recorded against the merchant like any other ruling, so that a pattern of \
                    agreement moves this merchant's line the same way a pattern of \
                    disagreement does.""")
    @ApiResponse(responseCode = "200", description = "The payment, refused")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "403",
            ref = "#/components/responses/FORBIDDEN",
            description = "An API key cannot rule on a review")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no payment with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "This payment is not waiting for anybody to rule on it")
    public PaymentResponse refuse(
            @PathVariable UUID merchantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody RulingRequest request,
            Caller caller) {

        return queue.refuse(caller, paymentId, request.why().trim());
    }

    /**
     * Why, and nothing else.
     *
     * <p>No name field on purpose: who ruled comes from the token. Required rather than
     * optional, for the same reason MIZ-53's operator decisions require one — a decision
     * nobody explained is not something the next person can learn from, and this is a decision
     * the platform itself learns from.
     */
    @Schema(description = "An analyst's reason for a ruling")
    public record RulingRequest(
            @Schema(example = "known customer, they called to confirm")
                    @NotBlank
                    @Size(max = 1000)
                    String why) {
    }
}
