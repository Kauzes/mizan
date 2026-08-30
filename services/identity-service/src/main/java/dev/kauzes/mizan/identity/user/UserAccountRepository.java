package dev.kauzes.mizan.identity.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import dev.kauzes.mizan.common.identity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** The email is stored lowercased, so a lookup has to be given it that way. */
    Optional<UserAccount> findByEmail(String email);

    /** Ordered so a listing is stable, rather than however the rows happen to come back. */
    List<UserAccount> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);

    /**
     * Scoped to the merchant on purpose. A caller asking about a user of another merchant and
     * a caller asking about a user who does not exist get the same answer, because from
     * inside this merchant those are the same thing.
     */
    Optional<UserAccount> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Query("select count(u) from UserAccount u join u.roles r "
            + "where u.merchant.id = :merchantId and r = :role")
    long countHolding(@Param("merchantId") UUID merchantId, @Param("role") Role role);
}
