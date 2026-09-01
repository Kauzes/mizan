package dev.kauzes.mizan.ledger.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** What a caller sends and sees when opening or reading an account. */
final class AccountRequests {

    private AccountRequests() {
    }

    @Schema(description = "An account to open")
    record OpenAccountRequest(
            @Schema(
                            description = "How this account is referred to. Unique within the "
                                    + "merchant, and not changeable afterwards.",
                            example = "settlement.try")
                    @NotBlank
                    @Size(max = 64)
                    @Pattern(
                            regexp = "[a-z][a-z0-9._-]*",
                            message = "must start with a letter and use only a-z, 0-9, dot, "
                                    + "dash and underscore")
                    String code,
            @Schema(description = "What it is, for a person reading a report",
                            example = "Settlement account, TRY")
                    @NotBlank
                    @Size(max = 200)
                    String name,
            @Schema(description = "Decides which way a posting moves this account")
                    @NotNull
                    AccountType type,
            @Schema(description = "ISO 4217. Fixed for the life of the account.", example = "TRY")
                    @NotBlank
                    @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO 4217 code")
                    String currency) {
    }

    @Schema(description = "An account in the books")
    record AccountResponse(
            UUID id,
            UUID merchantId,
            String code,
            String name,
            AccountType type,
            @Schema(
                            description = "Which side increases this account, from its type",
                            example = "DEBIT")
                    String normalSide,
            String currency,
            @Schema(
                            description =
                                    "The signed sum of this account's postings, debit positive, "
                                            + "in minor units. Not flipped to read naturally for "
                                            + "a liability: the type says which way to read it.",
                            example = "125000")
                    long balance,
            Instant createdAt) {

        static AccountResponse of(Account account) {
            return new AccountResponse(
                    account.id(),
                    account.merchantId(),
                    account.code(),
                    account.name(),
                    account.type(),
                    account.type().normalSide(),
                    account.currency().getCurrencyCode(),
                    account.balance(),
                    account.createdAt());
        }
    }

    @Schema(description = "What an account holds, as at the moment it was read")
    record BalanceResponse(
            UUID accountId,
            String code,
            AccountType type,
            @Schema(example = "DEBIT") String normalSide,
            String currency,
            @Schema(
                            description =
                                    "The signed sum of the account's postings, debit positive, "
                                            + "in minor units",
                            example = "125000")
                    long balance,
            @Schema(
                            description =
                                    "When this was read. A balance is only ever true as at a "
                                            + "moment.")
                    Instant readAt) {

        static BalanceResponse of(Account account) {
            return new BalanceResponse(
                    account.id(),
                    account.code(),
                    account.type(),
                    account.type().normalSide(),
                    account.currency().getCurrencyCode(),
                    account.balance(),
                    Instant.now());
        }
    }
}
