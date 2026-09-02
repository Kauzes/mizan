package dev.kauzes.mizan.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What a caller sends and sees when starting a payment and following it. */
final class PaymentRequests {

    private PaymentRequests() {
    }

    @Schema(description = "A payment about to be attempted")
    record CreatePaymentRequest(
            @Schema(
                            description = "Minor units. 12550 TRY means 125.50.",
                            example = "125000")
                    @Positive(message = "a payment is for more than nothing")
                    long amount,
            @Schema(description = "ISO 4217", example = "TRY")
                    @NotBlank
                    @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO 4217 code")
                    String currency,
            @Schema(
                            description =
                                    "What the merchant calls this payment. Unique within the "
                                            + "merchant, so their own reconciliation has "
                                            + "something to join on.",
                            example = "order-10241")
                    @NotBlank
                    @Size(max = 200)
                    String reference,
            @Schema(description = "For a person reading it later", example = "Two bags of coffee")
                    @Size(max = 500)
                    String description) {
    }

    @Schema(description = "The card to authorize against")
    record AuthorizeRequest(
            @Schema(
                            description =
                                    "The card. Only its last four digits are kept, and the "
                                            + "acquirer decides what to do from them: see its "
                                            + "own documentation for the catalogue.",
                            example = "4000000000000000")
                    @NotBlank
                    @Pattern(regexp = "[0-9]{12,19}", message = "must look like a card number")
                    String card) {

        /** A card number is not something to print. */
        @Override
        public String toString() {
            return "AuthorizeRequest[card=****]";
        }
    }

    @Schema(description = "One step a payment took")
    record TransitionResponse(
            @Schema(description = "Null for the first step") PaymentStatus from,
            PaymentStatus to,
            String reason,
            Instant at) {

        static TransitionResponse of(PaymentTransition transition) {
            return new TransitionResponse(
                    transition.from(), transition.to(), transition.reason(), transition.at());
        }
    }

    @Schema(description = "Why the reservation is being released")
    record VoidRequest(
            @Schema(
                            description =
                                    "Kept on the step, so somebody reading the history later "
                                            + "can see why rather than only that.",
                            example = "the customer cancelled the order")
                    @jakarta.validation.constraints.Size(max = 500)
                    String reason) {
    }

    @Schema(description = "A payment, and where it has got to")
    record PaymentResponse(
            UUID id,
            UUID merchantId,
            long amount,
            String currency,
            PaymentStatus status,
            @Schema(
                            description =
                                    "Where this payment may go next. Empty when it is finished.")
                    Set<PaymentStatus> allowedNext,
            String reference,
            String description,
            @Schema(description = "The acquirer's reference, once there is an authorization")
                    String acquirerReference,
            @Schema(description = "The last four digits of the card, and all that is kept")
                    String cardLastFour,
            @Schema(description = "Why the acquirer refused, if it did")
                    String declineReason,
            @Schema(
                            description =
                                    "The entry in the ledger that records the money moving. "
                                            + "Present once the payment is captured, and never "
                                            + "on one that was voided, because a released "
                                            + "reservation moved nothing.")
                    UUID ledgerEntryId,
            Instant createdAt,
            Instant updatedAt,
            @Schema(description = "Oldest first") List<TransitionResponse> history) {

        static PaymentResponse of(Payment payment) {
            return new PaymentResponse(
                    payment.id(),
                    payment.merchantId(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.status(),
                    payment.status().next(),
                    payment.reference(),
                    payment.description(),
                    payment.acquirerReference(),
                    payment.cardLastFour(),
                    payment.declineReason(),
                    payment.ledgerEntryId(),
                    payment.createdAt(),
                    payment.updatedAt(),
                    payment.history().stream().map(TransitionResponse::of).toList());
        }
    }
}
