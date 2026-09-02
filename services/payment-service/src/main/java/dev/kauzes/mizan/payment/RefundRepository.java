package dev.kauzes.mizan.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    /** What makes a retry safe: the merchant's own reference, within one payment. */
    Optional<Refund> findByPaymentIdAndReference(UUID paymentId, String reference);
}
