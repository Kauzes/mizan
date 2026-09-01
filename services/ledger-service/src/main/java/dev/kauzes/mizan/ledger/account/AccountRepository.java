package dev.kauzes.mizan.ledger.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByMerchantIdOrderByCodeAsc(UUID merchantId);

    /**
     * Scoped to the merchant, so another merchant's account is not found rather than found
     * and then hidden. A platform account has no merchant and is not reachable this way at
     * all, which is the intended answer under a merchant's path.
     */
    Optional<Account> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** For the platform's own accounts, looked up by the code a migration gave them. */
    Optional<Account> findByCodeAndMerchantIdIsNull(String code);
}
