package dev.kauzes.mizan.ledger.account;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.ledger.account.AccountRequests.AccountResponse;
import dev.kauzes.mizan.ledger.account.AccountRequests.BalanceResponse;
import dev.kauzes.mizan.ledger.account.AccountRequests.OpenAccountRequest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening accounts, and reading the ones a merchant has. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse open(UUID merchantId, OpenAccountRequest request) {
        Currency currency = currency(request.currency());

        Account account = new Account(
                merchantId, request.code().trim(), request.name().trim(), request.type(), currency);

        try {
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException taken) {
            // The code is the only thing a caller could collide on, and repeating it back
            // tells them nothing they did not just send.
            throw new ConflictException("This merchant already has an account with that code.");
        }

        log.info(
                "opened account {} for merchant {} as {} in {}",
                account.code(),
                merchantId,
                account.type(),
                currency.getCurrencyCode());
        return AccountResponse.of(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID merchantId) {
        return accounts.findByMerchantIdOrderByCodeAsc(merchantId).stream()
                .map(AccountResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse find(UUID merchantId, UUID accountId) {
        return accounts
                .findByIdAndMerchantId(accountId, merchantId)
                .map(AccountResponse::of)
                .orElseThrow(() -> new NotFoundException("No account with that id."));
    }

    /**
     * What an account holds, read from the account rather than summed from its history.
     *
     * <p>One row, whatever the account has been through. The number is only true as at the
     * moment it is read, which the answer says.
     */
    @Transactional(readOnly = true)
    public BalanceResponse balanceOf(UUID merchantId, UUID accountId) {
        return accounts
                .findByIdAndMerchantId(accountId, merchantId)
                .map(BalanceResponse::of)
                .orElseThrow(() -> new NotFoundException("No account with that id."));
    }

    /**
     * The pattern has already established this is three capital letters. Whether those three
     * letters name a currency is a different question, and the answer decides what every
     * amount in this account means.
     */
    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException unknown) {
            throw new UnprocessableException(code + " is not a currency this platform knows.");
        }
    }
}
