package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How business is, for the screen a merchant lands on.
 *
 * <p>Its own route rather than a shape on the payments list, because it answers a different
 * kind of question: the list is about payments and this is about all of them at once.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/summary",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Payments", description = "Taking money, from intent to captured")
public class SummaryController {

    private final HowBusinessIs summary;

    public SummaryController(HowBusinessIs summary) {
        this.summary = summary;
    }

    @GetMapping
    @RequiresPermission(Permission.PAYMENT_READ)
    @Operation(
            summary = "How business is",
            description =
                    """
                    Counted by the database, never by the caller. What was attempted, what \
                    went through, what money moved per currency, a row per day with the volume \
                    behind each rate, why payments were refused split between the acquirer and \
                    this platform, and what is outstanding right now.

                    The authorization rate is null rather than zero when nothing was \
                    attempted: a rate of zero says every payment failed, and no payments at \
                    all says something else entirely.

                    What needs somebody is not scoped to the range. A payment held three weeks \
                    ago is still held, and nobody should have to widen a date filter to find \
                    out that a customer is waiting.""")
    @ApiResponse(responseCode = "200", description = "The summary")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "A range that ends before it starts, or is longer than this answers")
    public Map<String, Object> summary(
            @PathVariable UUID merchantId,
            @Parameter(description = "Inclusive. Defaults to thirty days ago.")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(description = "Exclusive. Defaults to now.")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @Parameter(
                            description =
                                    "Which days these are, as an IANA zone. A merchant in "
                                            + "Istanbul asking about Tuesday means their "
                                            + "Tuesday.",
                            example = "Europe/Istanbul")
                    @RequestParam(required = false)
                    String zone) {

        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(30, ChronoUnit.DAYS) : from;
        return summary.forMerchant(merchantId, start, end, zone);
    }
}
