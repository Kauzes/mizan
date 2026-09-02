package dev.kauzes.mizan.banksim;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** What a caller sends this acquirer and what it says back. */
final class AcquirerRequests {

    private AcquirerRequests() {
    }

    @Schema(description = "An authorization to attempt")
    record AuthorizeRequest(
            @Schema(
                            description =
                                    "The caller's own identifier for this attempt. Sending it "
                                            + "again returns the first outcome rather than "
                                            + "authorizing twice.",
                            example = "payment-8f21c0d4-attempt-1")
                    @NotBlank
                    @Size(max = 200)
                    String requestId,
            @Schema(description = "Minor units", example = "125000") @Positive long amount,
            @Schema(example = "TRY") @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Schema(
                            description =
                                    "The last four digits decide what this acquirer does. See "
                                            + "the description of this operation.",
                            example = "4000000000000000")
                    @NotBlank
                    @Pattern(regexp = "[0-9]{12,19}", message = "must look like a card number")
                    String card) {

        /** Deliberately says nothing about the card, whatever logs this. */
        @Override
        public String toString() {
            return "AuthorizeRequest[requestId=" + requestId + ", amount=" + amount
                    + ", currency=" + currency + ", card=****]";
        }
    }

    @Schema(description = "What the acquirer decided")
    record AuthorizationResponse(
            @Schema(
                            description = "The acquirer's own reference. Captures and voids name "
                                    + "this.",
                            example = "auth_7Qv3nR2xKp0L")
                    String acquirerReference,
            String requestId,
            AuthorizationOutcome outcome,
            @Schema(description = "Why it was refused, or null if it was not")
                    String reason,
            long amount,
            String currency,
            @Schema(description = "The last four digits of the card, which is all that is kept")
                    String cardLastFour,
            Instant decidedAt,
            @Schema(description = "Whether the money has since been taken or released")
                    AuthorizationState state) {
    }
}
