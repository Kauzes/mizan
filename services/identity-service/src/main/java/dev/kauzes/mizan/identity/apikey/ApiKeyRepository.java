package dev.kauzes.mizan.identity.apikey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Looked up on every signed request. Not scoped to a merchant, because at this point
     * nobody has said which merchant is calling: the key is what says so.
     */
    Optional<ApiKey> findByKeyId(String keyId);

    List<ApiKey> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);

    /** Scoped, so a caller cannot revoke a key belonging to somebody else. */
    Optional<ApiKey> findByIdAndMerchantId(UUID id, UUID merchantId);
}
