package dev.kauzes.mizan.ledger.account;

import dev.kauzes.mizan.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a merchant's account holds, for a service that has to know before it moves money.
 *
 * <p>Settlement needs this: paying out more than the platform owes would be paying a merchant
 * with somebody else's money, and the only thing that knows what is owed is the books. It
 * cannot be asked through the merchant route, because doing so would mean this platform's own
 * services forging a merchant's identity to read their data — which is exactly the boundary
 * the internal routes exist to keep honest.
 *
 * <p>Read only, and by code rather than by id, for the same reason the internal entry route
 * is: the ids belong to a migration in this service's database and no other service should be
 * holding them.
 */
@RestController
@RequestMapping(path = "/internal/balances", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
        name = "Accounts (internal)",
        description =
                "What an account holds, for a service that must not move money without "
                        + "knowing. Not reachable from the edge.")
public class InternalBalanceController {

    private final AccountRepository accounts;

    public InternalBalanceController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/{merchantId}/{code}")
    @Operation(
            summary = "What one of a merchant's accounts holds",
            description =
                    """
                    The signed sum of its postings, debit positive, in minor units. Not \
                    flipped to read naturally for a liability: the type says which way to \
                    read it, and a caller that wants to know what the platform owes a \
                    merchant is asking for a negative number.""")
    @ApiResponse(responseCode = "200", description = "The account and its balance")
    @ApiResponse(
            responseCode = "401",
            ref = "#/components/responses/UNAUTHORIZED",
            description = "No service credential")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no account with that code")
    public Map<String, Object> balance(@PathVariable UUID merchantId, @PathVariable String code) {
        Account account = accounts
                .findByMerchantIdAndCode(merchantId, code)
                .orElseThrow(() -> new NotFoundException(
                        "No account " + code + " in this merchant's books."));

        return Map.of(
                "code", account.code(),
                "type", account.type().name(),
                "currency", account.currency().getCurrencyCode(),
                "balance", account.balance());
    }
}
