package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.ledger.account.Account;
import dev.kauzes.mizan.ledger.account.AccountRepository;
import dev.kauzes.mizan.ledger.journal.JournalRequests.EntryResponse;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostEntryRequest;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Posting an entry that crosses between a merchant's books and the platform's.
 *
 * <p>A capture is money arriving at the platform on a merchant's behalf, so the entry that
 * records it touches an account on each side. No merchant-scoped endpoint may write that, and
 * this one is deliberately not a merchant endpoint: it is outside {@code /api/}, the edge does
 * not route to it, and it requires the service credential. A merchant who reached it anyway
 * would still only be able to name their own accounts and the platform's.
 *
 * <p>Accounts are named by code rather than by id, because the caller is another service that
 * knows what a capture means and should not have to hold the ids a migration handed out. A
 * code beginning with {@code platform.} is the platform's; anything else is this merchant's.
 * The chart of accounts is still the ledger's, and an account nobody opened is refused rather
 * than created — ADR 0011 is why.
 */
@RestController
@RequestMapping(path = "/internal/entries", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
        name = "Journal (internal)",
        description =
                "Entries that cross between a merchant's books and the platform's. Not "
                        + "reachable from the edge, and not part of the merchant API.")
public class InternalEntryController {

    /** Everything the platform owns is named this way, and nothing a merchant owns is. */
    private static final String PLATFORM = "platform.";

    private final JournalService journal;
    private final AccountRepository accounts;

    public InternalEntryController(JournalService journal, AccountRepository accounts) {
        this.journal = journal;
        this.accounts = accounts;
    }

    @PostMapping
    @Operation(
            summary = "Post an entry across the platform's books and a merchant's",
            description =
                    """
                    Idempotent on externalReference, exactly as the merchant-facing post is: \
                    a caller that never heard the answer sends the same request again and is \
                    given the entry the first call wrote. That is what makes a capture safe \
                    to retry.""")
    @ApiResponse(responseCode = "201", description = "The entry, or the one already written")
    @ApiResponse(
            responseCode = "401",
            ref = "#/components/responses/UNAUTHORIZED",
            description = "No service credential")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description =
                    "The postings do not balance, or name an account that is neither this "
                            + "merchant's nor the platform's")
    public org.springframework.http.ResponseEntity<EntryResponse> post(
            @Valid @RequestBody InternalEntryRequest request) {

        List<PostingRequest> postings = request.postings().stream()
                .map(posting -> new PostingRequest(
                        idOf(request.merchantId(), posting.accountCode()), posting.amount()))
                .toList();

        EntryResponse posted = journal.post(
                request.merchantId(),
                new PostEntryRequest(
                        request.externalReference(),
                        request.description(),
                        request.occurredAt(),
                        request.corrects(),
                        postings),
                JournalService.Books.THE_MERCHANTS_AND_THE_PLATFORMS);

        return org.springframework.http.ResponseEntity.status(201).body(posted);
    }

    /**
     * A code becomes an id here, and an account that was never opened is refused by name.
     *
     * <p>By name, because "no account 8f21…" tells an operator nothing and "no account
     * settlement.try in this merchant's books" tells them what to open.
     */
    private UUID idOf(UUID merchantId, String code) {
        if (code.startsWith(PLATFORM)) {
            return accounts
                    .findByCodeAndMerchantIdIsNull(code)
                    .map(Account::id)
                    .orElseThrow(() -> new UnprocessableException(
                            "The platform has no account " + code + "."));
        }
        return accounts
                .findByMerchantIdAndCode(merchantId, code)
                .map(Account::id)
                .orElseThrow(() -> new UnprocessableException(
                        "No account " + code + " in this merchant's books. It has to be opened "
                                + "before money can be recorded as arriving in it."));
    }

    @Schema(description = "One side of a movement, naming its account by code")
    public record InternalPosting(
            @Schema(example = "platform.clearing.try") @NotBlank @Size(max = 100)
                    String accountCode,
            @Schema(description = "Signed minor units. Positive is a debit.", example = "125000")
                    long amount) {
    }

    @Schema(description = "A movement that may cross between two sets of books")
    public record InternalEntryRequest(
            @Schema(description = "Whose books the merchant side of this entry belongs to")
                    @NotNull
                    UUID merchantId,
            @Schema(example = "payment:8f21c0d4-capture") @NotBlank @Size(max = 200)
                    String externalReference,
            @Schema(example = "Card payment captured") @NotBlank @Size(max = 500)
                    String description,
            @NotNull Instant occurredAt,
            @Schema(
                            description =
                                    "The entry this one corrects, if it is a correction. A "
                                            + "refund names the capture it gives back, so the "
                                            + "two are readable together and neither is edited.")
                    UUID corrects,
            @NotNull @Size(min = 2, message = "an entry moves money between at least two accounts")
                    List<@Valid InternalPosting> postings) {
    }
}
