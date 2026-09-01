package dev.kauzes.mizan.ledger.account;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.ledger.account.AccountRequests.AccountResponse;
import dev.kauzes.mizan.ledger.account.AccountRequests.OpenAccountRequest;
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
 * A merchant's chart of accounts.
 *
 * <p>Every path names the merchant, so the caller is checked against it before a handler
 * runs. There is no endpoint here that opens a platform account: those belong to nobody in
 * particular, every merchant's money passes through them, and they are created by migration
 * rather than by a request arriving.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/accounts",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts", description = "The chart of accounts a merchant's money moves through")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @RequiresPermission(Permission.ACCOUNT_READ)
    @Operation(summary = "List a merchant's accounts", description = "Ordered by code.")
    @ApiResponse(responseCode = "200", description = "The merchant's accounts")
    public List<AccountResponse> list(@PathVariable UUID merchantId) {
        return accounts.list(merchantId);
    }

    @PostMapping
    @RequiresPermission(Permission.ACCOUNT_MANAGE)
    @Operation(
            summary = "Open an account",
            description =
                    "The type decides which way a posting moves this account, and the currency "
                            + "is fixed for its life. Neither can be changed afterwards, because "
                            + "either would reinterpret history already written against it.")
    @ApiResponse(responseCode = "201", description = "The account that was opened")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "This merchant already has an account with that code")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "Those three letters do not name a currency")
    public ResponseEntity<AccountResponse> open(
            @PathVariable UUID merchantId, @Valid @RequestBody OpenAccountRequest request) {

        AccountResponse opened = accounts.open(merchantId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/accounts/" + opened.id()))
                .body(opened);
    }

    @GetMapping("/{accountId}")
    @RequiresPermission(Permission.ACCOUNT_READ)
    @Operation(summary = "Read an account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no account with that id")
    public AccountResponse find(@PathVariable UUID merchantId, @PathVariable UUID accountId) {
        return accounts.find(merchantId, accountId);
    }
}
