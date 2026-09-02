package dev.kauzes.mizan.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    /** What makes a retry safe: the merchant's own reference, within one payment. */
    Optional<Refund> findByPaymentIdAndReference(UUID paymentId, String reference);

    /**
     * Refunds that stopped halfway and are due another attempt.
     *
     * <p>Older than the settle time, so a refund whose original call is still in flight is not
     * raced for no benefit, and past their backoff, so one that keeps failing is not retried in
     * a loop.
     */
    @org.springframework.data.jpa.repository.Query(
            "select r from Refund r where r.status in ('REQUESTED', 'RETURNED') "
                    + "and r.createdAt < :before "
                    + "and (r.nextAttemptAt is null or r.nextAttemptAt <= :now) "
                    + "order by r.createdAt")
    List<Refund> findUnfinishedBefore(
            @org.springframework.data.repository.query.Param("before") java.time.Instant before,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    /** What needs a person: refunds nobody could finish. */
    List<Refund> findByStatusOrderByUpdatedAtDesc(RefundStatus status);
}
