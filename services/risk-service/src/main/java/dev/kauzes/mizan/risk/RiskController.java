package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import dev.kauzes.mizan.risk.RiskRequests.ScoreRequest;
import dev.kauzes.mizan.risk.RiskRequests.ScoreResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scoring, as something another service asks for.
 *
 * <p>Not under a merchant's path, because a merchant does not score their own payments and
 * should not be able to ask what this platform thinks of one — knowing exactly what trips the
 * scorer is most useful to somebody trying not to trip it.
 */
@RestController
@RequestMapping(path = "/api/v1/risk", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Risk", description = "What this platform thinks of a payment, and why")
public class RiskController {

    private final RiskService risk;

    public RiskController(RiskService risk) {
        this.risk = risk;
    }

    @PostMapping("/scores")
    @PublicEndpoint(
            because = "scoring is asked for by the payment service on a caller's behalf, not by "
                    + "the caller. It holds no data and changes nothing: the request carries "
                    + "everything it is scored against, so there is nothing here to authorize "
                    + "access to. MIZ-58 is what puts it behind the service credential when the "
                    + "payment flow actually calls it.")
    @NotIdempotent(
            because = "scoring changes nothing, so asking twice is asking the same question "
                    + "twice and gets the same answer without needing a record of the first.")
    @Operation(
            summary = "Score a payment",
            description =
                    """
                    Returns approve, review or block, with everything that fired and what each \
                    was worth. The reasons are the point: a score with no reasons is a number \
                    nobody can argue with, and a decision nobody can argue with is one nobody \
                    can fix.

                    Scoring is a pure function of the request. The same request scores the same \
                    way twice, including the timestamp, which is an input rather than a clock \
                    read inside.

                    No card number is sent or accepted. The caller supplies a fingerprint, so \
                    that the same card is recognisable across payments without this service \
                    ever holding one.""")
    @ApiResponse(responseCode = "200", description = "What it decided, and why")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    public ScoreResponse score(@Valid @RequestBody ScoreRequest request) {
        return risk.score(request);
    }
}
