package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a merchant is owed, and what made it up.
 *
 * <p>Read only. A settlement is a consequence of payments that already happened, so there is
 * nothing here for a merchant to change: the only write is closing a day, which is the
 * platform's own job and lives behind an operator endpoint.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/settlements",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Settlements", description = "The difference between taking money and being paid")
public class SettlementController {

    private final Settlement settlement;

    public SettlementController(Settlement settlement) {
        this.settlement = settlement;
    }

    @GetMapping
    @RequiresPermission(Permission.ACCOUNT_READ)
    @Operation(
            summary = "A merchant's settlement batches",
            description =
                    """
                    One batch per day per currency: what was captured, what this platform \
                    charged for taking it, and what the merchant is therefore owed. Most \
                    recently settled first.

                    All three figures are stored rather than worked out on the way out, along \
                    with the fee rule as it was applied. A settlement a merchant has already \
                    been shown must not move when the rule changes.

                    Refunds are not netted in. Money going back has its own timing and its \
                    own movement in the books, and hiding it inside a settlement total is how \
                    a merchant loses the ability to see either.""")
    @ApiResponse(responseCode = "200", description = "The batches, and the totals across them")
    public Map<String, Object> batches(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "60") int limit) {

        return Map.of(
                "batches", settlement.forMerchant(merchantId, Math.min(Math.max(limit, 1), 400)),
                "totals", settlement.owedTo(merchantId));
    }

    @GetMapping("/{batchId}/items")
    @RequiresPermission(Permission.ACCOUNT_READ)
    @Operation(
            summary = "The payments that made up a batch",
            description =
                    """
                    Each with what it contributed to the fee. Those contributions add up to \
                    exactly the fee on the batch: the percentage is worked out once on the \
                    total and then allocated across the payments, so a merchant adding up \
                    their own statement gets the number they were charged.""")
    @ApiResponse(responseCode = "200", description = "The payments in the batch")
    public List<Map<String, Object>> items(
            @PathVariable UUID merchantId, @PathVariable UUID batchId) {

        return settlement.itemsOf(merchantId, batchId);
    }
}
