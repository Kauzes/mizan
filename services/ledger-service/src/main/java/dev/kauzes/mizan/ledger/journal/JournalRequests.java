package dev.kauzes.mizan.ledger.journal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What a caller sends and sees when writing to the books. */
final class JournalRequests {

    private JournalRequests() {
    }

    @Schema(description = "One side of a movement")
    record PostingRequest(
            @Schema(description = "The account this side moves") @NotNull UUID accountId,
            @Schema(
                            description =
                                    "Signed minor units, in the account's currency. Positive is "
                                            + "a debit, negative is a credit, and zero says "
                                            + "nothing. Whether a debit increases this account "
                                            + "is decided by its type.",
                            example = "125000")
                    long amount) {
    }

    @Schema(description = "A movement of money, as postings that sum to zero")
    record PostEntryRequest(
            @Schema(example = "Card payment captured") @NotBlank @Size(max = 500)
                    String description,
            @Schema(description = "When the money moved, which need not be now")
                    @NotNull
                    Instant occurredAt,
            @Schema(description = "The entry this one corrects, if it is a correction")
                    UUID corrects,
            @Schema(description = "At least two, summing to zero within each currency")
                    @NotNull
                    @Size(min = 2, message = "an entry moves money between at least two accounts")
                    List<@Valid PostingRequest> postings) {
    }

    @Schema(description = "One side of a movement, as written")
    record PostingResponse(
            UUID id,
            UUID accountId,
            String accountCode,
            long amount,
            String currency,
            @Schema(example = "DEBIT") String direction) {

        static PostingResponse of(Posting posting) {
            return new PostingResponse(
                    posting.id(),
                    posting.account().id(),
                    posting.account().code(),
                    posting.amount(),
                    posting.account().currency().getCurrencyCode(),
                    posting.direction());
        }
    }

    @Schema(description = "A movement of money, as written")
    record EntryResponse(
            UUID id,
            UUID merchantId,
            String description,
            Instant occurredAt,
            @Schema(description = "When this was written, which is not when the money moved")
                    Instant recordedAt,
            UUID corrects,
            List<PostingResponse> postings) {

        static EntryResponse of(JournalEntry entry) {
            return new EntryResponse(
                    entry.id(),
                    entry.merchantId(),
                    entry.description(),
                    entry.occurredAt(),
                    entry.recordedAt(),
                    entry.correctsId(),
                    entry.postings().stream()
                            .map(PostingResponse::of)
                            .sorted(java.util.Comparator.comparing(PostingResponse::accountCode))
                            .toList());
        }
    }
}
