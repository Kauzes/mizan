package dev.kauzes.mizan.identity.user;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** Ordered so a listing is stable, rather than however the rows happen to come back. */
    List<UserAccount> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);
}
