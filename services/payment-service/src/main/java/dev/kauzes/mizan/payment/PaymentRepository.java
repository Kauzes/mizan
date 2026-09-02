package dev.kauzes.mizan.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Scoped, so another merchant's payment is not found rather than found and hidden. */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payment> findByMerchantIdAndReference(UUID merchantId, String reference);

    List<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /** What the resolver sweeps: payments nobody knows the outcome of, that have settled. */
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before);
}
