package dev.kauzes.mizan.banksim;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily statement, in the format an acquirer would actually send.
 *
 * <p>A file rather than JSON, and pipe delimited with a header and a trailer, because that is
 * what arrives from a bank and reading somebody else's format is the part of reconciliation
 * that is actually hard. A simulator that handed the platform its own API shape back would
 * have skipped the difficulty entirely.
 *
 * <p>And it disagrees with the platform on purpose. See {@link Statements}: there is no way to
 * tell a reconciliation job that finds real differences from one that finds none, unless there
 * are differences to find.
 */
@RestController
@RequestMapping(path = "/statements", produces = MediaType.TEXT_PLAIN_VALUE)
@Tag(
        name = "Acquirer",
        description = "A bank that approves, declines, times out, duplicates and disagrees")
public class StatementController {

    private final Statements statements;

    public StatementController(Statements statements) {
        this.statements = statements;
    }

    @GetMapping("/{day}")
    @Operation(
            summary = "What this acquirer settled on a day",
            description =
                    """
                    A pipe delimited file: one `H` header naming the day and currency, a `D` \
                    row per transaction with its reference and amount in minor units, and a \
                    `T` trailer with the count and the total. The trailer agrees with the \
                    rows above it, so a truncated file is detectable.

                    Deliberately not a copy of what the platform believes. Three \
                    disagreements are introduced, deterministically: one transaction the \
                    platform has and this does not, one this has that the platform never \
                    issued, and one both have for amounts that differ by a minor unit. Ask \
                    with `faithful=true` for a statement with none of them, which is how a \
                    caller proves reconciliation finds nothing when there is nothing to find.

                    A day that has ended answers the same way every time. A day still \
                    running will not, because more transactions keep settling into it.""")
    @ApiResponse(responseCode = "200", description = "The statement file")
    public String forDay(
            @Parameter(description = "The settlement date, as YYYY-MM-DD")
                    @PathVariable
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate day,
            @Parameter(description = "Statements are per currency, as a real acquirer's are")
                    @RequestParam(defaultValue = "TRY")
                    String currency,
            @Parameter(description = "No deliberate disagreements")
                    @RequestParam(defaultValue = "false")
                    boolean faithful) {

        return statements.forDay(day, currency.toUpperCase(java.util.Locale.ROOT), faithful);
    }
}
