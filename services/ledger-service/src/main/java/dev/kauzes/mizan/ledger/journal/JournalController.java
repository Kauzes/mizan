package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.ledger.journal.JournalRequests.EntryResponse;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostEntryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    @Operation(summary = "List a merchant's entries", description = "Most recent movement first.")
    @ApiResponse(responseCode = "200", description = "The merchant's entries")
    public List<EntryResponse> list(@PathVariable UUID merchantId) {
        return journal.list(merchantId);
    }

    @PostMapping
    @RequiresPermission(Permission.ENTRY_POST)
    @Operation(
            summary = "Post an entry",
            description =
                    "The postings must sum to zero within each currency they touch. A positive "
                            + "amount is a debit; whether that increases the account is decided "
                            + "by the account's type. Nothing posted here can be changed "
                            + "afterwards.")
    @ApiResponse(responseCode = "201", description = "The entry as written")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
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
