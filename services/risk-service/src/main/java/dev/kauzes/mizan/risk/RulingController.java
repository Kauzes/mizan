package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telling risk what an analyst decided.
 *
 * <p>Called by the payment service when a ruling is made, not by an analyst directly. The queue
 * and the ruling live where the payment lives; this is the half that learns from it. Keeping
 * them apart means the payment service never has to know how the scorer is tuned, and the
 * scorer never has to know how a payment is released.
 */
@RestController
@RequestMapping(path = "/api/v1/risk/rulings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Risk", description = "What this platform thinks of a payment, and why")
public class RulingController {

    private final Rulings rulings;

    public RulingController(Rulings rulings) {
        this.rulings = rulings;
    }

    @PostMapping
    @PublicEndpoint(
            because = "told by the payment service on an analyst's behalf, not by the analyst. "
                    + "Whether they were allowed to rule is decided where the payment is, "
                    + "because that is where the merchant is known. The service credential is "
                    + "what will guard this path.")
    @NotIdempotent(
            because = "a payment can only be ruled on once, and the second attempt is refused "
                    + "by the database rather than answered from a record of the first.")
    @Operation(
            summary = "Record what an analyst decided",
            description =
                    """
                    Records the ruling and, if the last few went the same way, moves this \
                    merchant's line.

                    The movement is deliberately slow and bounded. It takes several consecutive \
                    rulings the same way before anything moves at all, it moves a few points \
                    when it does, and it can never drift more than twenty from where a person \
                    set it. A loop nobody bounded is a loop an attacker teaches, one released \
                    payment at a time.

                    What a person set and what the loop inferred are kept separately, so the \
                    drift is always visible and always reversible.""")
    @ApiResponse(responseCode = "200", description = "The ruling, and what it moved")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "This payment has already been ruled on")
    public Map<String, Object> rule(@Valid @RequestBody RulingRequest request) {
        return rulings.record(
                request.merchantId(),
                request.paymentId(),
                request.ruling(),
                request.riskScore(),
                request.riskReasons(),
                request.ruledBy(),
                request.why());
    }

    @GetMapping("/merchants/{merchantId}")
    @PublicEndpoint(
            because = "the same reason as recording one: read by the payment service showing an "
                    + "analyst what has been ruled, and holding no merchant data beyond what "
                    + "that service already has.")
    @Operation(
            summary = "What has been ruled for a merchant",
            description = "Most recent first, with what the scorer had said at the time.")
    @ApiResponse(responseCode = "200", description = "The rulings")
    public Map<String, Object> forMerchant(
            @PathVariable UUID merchantId, @RequestParam(defaultValue = "50") int limit) {

        List<Map<String, Object>> recent =
                rulings.forMerchant(merchantId, Math.min(Math.max(limit, 1), 200));

        return Map.of(
                "rulings", recent,
                // Shown beside them, because "what have my analysts decided" and "what has the
                // platform learned from that" are the same question asked twice.
                "learnedAdjustment", rulings.adjustmentFor(merchantId));
    }

    @Schema(description = "What an analyst decided about a held payment")
    public record RulingRequest(
            @NotNull UUID merchantId,
            @NotNull UUID paymentId,
            @Schema(description = "RELEASED or REFUSED", example = "RELEASED")
                    @NotBlank
                    @Pattern(regexp = "RELEASED|REFUSED", message = "must be RELEASED or REFUSED")
                    String ruling,
            @Schema(description = "What the scorer had said at the time") Integer riskScore,
            @Schema(description = "And why it said it") @Size(max = 2000) String riskReasons,
            @Schema(example = "grace") @NotBlank @Size(max = 200) String ruledBy,
            @Schema(example = "known customer, they called to confirm")
                    @NotBlank
                    @Size(max = 1000)
                    String why) {
    }
}
