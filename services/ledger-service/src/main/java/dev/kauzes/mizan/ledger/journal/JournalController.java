package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.Pages;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.ledger.journal.JournalRequests.EntryResponse;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostEntryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The journal: what has moved, and where it moved between.
 *
 * <p>There is no endpoint here that changes or removes an entry, and there will not be one.
 * A mistake is corrected by posting a new entry that names the one it corrects, which leaves
 * both visible.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/entries",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Journal", description = "Movements of money, as entries that sum to zero")
public class JournalController {

    private final JournalService journal;

    public JournalController(JournalService journal) {
        this.journal = journal;
    }

    @GetMapping
    @RequiresPermission(Permission.ENTRY_READ)
    @Operation(
            summary = "List a merchant's entries",
            description =
                    """
                    Most recent movement first, a page at a time. Narrowed to one account with \
                    `accountId`, which is what somebody following a balance back to what made \
                    it is asking for.

                    Where the page sits comes back in headers — `X-Total-Count`, `X-Page`, \
                    `X-Page-Size` and `Link` — so the body is the same list of entries it has \
                    always been.""")
    @ApiResponse(responseCode = "200", description = "The page of entries")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "A page beyond where this pages")
    public ResponseEntity<List<EntryResponse>> list(
            @PathVariable UUID merchantId,
            @Parameter(description = "Only entries that touched this account")
                    @RequestParam(required = false)
                    UUID accountId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            UriComponentsBuilder uris) {

        int wantedSize = size == null ? 50 : size;
        int wantedPage = page == null ? 0 : page;
        if (wantedSize < 1 || wantedSize > 200) {
            throw new UnprocessableException("A page holds between 1 and 200 entries.");
        }
        if (wantedPage < 0 || (long) wantedPage * wantedSize > 10_000) {
            // The same bound as payments, for the same reason: an offset is read by counting
            // past every row before it. Somebody looking that far back wants a date range.
            throw new UnprocessableException(
                    "This platform pages 10,000 entries deep. Further back than that, ask "
                            + "about an account rather than turning pages.");
        }

        JournalService.Found found = journal.list(merchantId, accountId, wantedPage, wantedSize);
        return ResponseEntity.ok()
                .headers(headers ->
                        Pages.describe(headers, uris, found.total(), found.page(), found.size()))
                .body(found.entries());
    }

    @PostMapping
    @RequiresPermission(Permission.ENTRY_POST)
    @NotIdempotent(
            because = "an entry carries its own external reference, which does this and "
                    + "also ties the entry to what caused it. See MIZ-36.")
    @Operation(
            summary = "Post an entry",
            description =
                    """
                    The postings must sum to zero within each currency they touch. A positive \
                    amount is a debit; whether that increases the account is decided by the \
                    account's type. Nothing posted here can be changed afterwards.

                    The call is idempotent on externalReference: sending the same reference \
                    again returns the entry the first call wrote, with the same id and the \
                    same status, so a retry after a dropped response is safe. The same \
                    reference sent with different postings is refused.""")
    @ApiResponse(
            responseCode = "201",
            description =
                    "The entry as written, or the one an earlier call with this reference "
                            + "wrote")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "That reference was already used for a different entry")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description =
                    "The postings do not balance, name an account outside this merchant's "
                            + "books, or correct an entry that is not there")
    public ResponseEntity<EntryResponse> post(
            @PathVariable UUID merchantId, @Valid @RequestBody PostEntryRequest request) {

        EntryResponse posted = journal.post(merchantId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/entries/" + posted.id()))
                .body(posted);
    }

    @GetMapping("/{entryId}")
    @RequiresPermission(Permission.ENTRY_READ)
    @Operation(summary = "Read an entry and its postings")
    @ApiResponse(responseCode = "200", description = "The entry")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no entry with that id")
    public EntryResponse find(@PathVariable UUID merchantId, @PathVariable UUID entryId) {
        return journal.find(merchantId, entryId);
    }
}
