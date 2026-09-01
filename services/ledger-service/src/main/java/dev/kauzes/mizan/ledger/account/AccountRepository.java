package dev.kauzes.mizan.ledger.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByMerchantIdOrderByCodeAsc(UUID merchantId);

    /**
     * Scoped to the merchant, so another merchant's account is not found rather than found
     * and then hidden. A platform account has no merchant and is not reachable this way at
     * all, which is the intended answer under a merchant's path.
     */
    Optional<Account> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * The same lookup, holding the row until the transaction ends.
     *
     * <p>Used when posting, so that writers reaching one account queue rather than race. They
     * raced before, and lost: a writer that blocks and then fails its version check has to
     * redo everything, so with n writers at one account the last one needed n - 1 retries and
     * the retry budget became a limit on how many callers an account could have.
     *
     * <p>Callers lock in a consistent order. Two entries naming the same pair of accounts the
     * other way round would otherwise be able to hold what the other needs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findForUpdateByIdAndMerchantId(UUID id, UUID merchantId);

    /** For the platform's own accounts, looked up by the code a migration gave them. */
    Optional<Account> findByCodeAndMerchantIdIsNull(String code);
}
