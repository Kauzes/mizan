package dev.kauzes.mizan.risk;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What a caller sends to be scored, and what comes back. */
public final class RiskRequests {

    private RiskRequests() {
    }

    /**
     * A payment to score.
     *
     * <p>Everything the scorer uses is in here, including the things it might have looked up
     * for itself. That is what makes scoring a pure function of its input: the same request
     * scores the same way twice, and a test can construct any situation without arranging the
     * world to produce it.
     *
     * <p>It also keeps the boundary honest. Risk does not read the payment database — the
     * caller says what the payment is, and MIZ-57's baselines come from events rather than
     * from reaching across.
     */
    @Schema(description = "A payment to score")
    public record ScoreRequest(
            @Schema(description = "The payment being scored") @NotNull UUID paymentId,
            @NotNull UUID merchantId,
            @Schema(description = "Minor units", example = "125000") @Positive long amount,
            @Schema(example = "TRY") @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Schema(
                            description =
                                    "A stable identifier for the card, not the card. A "
                                            + "fingerprint the caller computes, so that the same "
                                            + "card is recognisable across payments without this "
                                            + "service ever holding a card number.",
                            example = "card_9f2b1c")
                    @NotBlank
                    @Size(max = 100)
                    String cardFingerprint,
            @Schema(description = "Where the card was issued", example = "TR")
                    @Pattern(regexp = "[A-Z]{2}", message = "must be a two letter country code")
                    String cardCountry,
            @Schema(
                            description =
                                    "When this is being scored. An input rather than a clock "
                                            + "read inside, so that the same request scores the "
                                            + "same way twice and a test can place a payment in "
                                            + "time without waiting.")
                    @NotNull
                    Instant at) {
    }

    @Schema(description = "What the scorer decided, and why")
    public record ScoreResponse(
            UUID paymentId,
            @Schema(description = "APPROVE, REVIEW or BLOCK") Verdict verdict,
            @Schema(description = "What it added up to. Higher is riskier.", example = "55")
                    int score,
            @Schema(
                            description =
                                    "Where this merchant's line between approve and review sits, "
                                            + "and between review and block")
                    int reviewAbove,
            int blockAbove,
            @Schema(description = "Everything that fired, and what each was worth")
                    List<Signal> signals,
            @Schema(description = "When it was scored") Instant at) {
    }
}
