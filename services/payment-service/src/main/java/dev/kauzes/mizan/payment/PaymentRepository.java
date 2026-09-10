package dev.kauzes.mizan.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * The same lookup, holding the row until the transaction ends.
     *
     * <p>Used when refunding. Two refunds arriving at once must not each read the same
     * remaining amount and both decide there is room: the limit is only a limit if the read
     * and the write are one thing. The same lesson as MIZ-39, where optimistic retries turned
     * out to be a cap on how many callers an account could have.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<Payment> findForUpdateByIdAndMerchantId(UUID id, UUID merchantId);

    /** Scoped, so another merchant's payment is not found rather than found and hidden. */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payment> findByMerchantIdAndReference(UUID merchantId, String reference);

    List<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /** What the resolver sweeps: payments nobody knows the outcome of, that have settled. */
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before);

    /**
     * Unresolved payments the platform has not yet given up on.
     *
     * <p>The sweep used to ask about every unresolved payment on every pass, forever. That is
     * right while there is any chance of an answer and wrong once there plainly is not, so
     * ones that need a person are left out of it.
     */
    List<Payment> findByStatusAndUpdatedAtBeforeAndNeedsAttentionSinceIsNull(
            PaymentStatus status, Instant before);

    /**
     * Payments held for review that nobody has ruled on, since before a moment.
     *
     * <p>The sweep must skip a released payment: a person has already decided, and expiring it
     * would overrule them by doing nothing, which is the worst way to be overruled.
     */
    List<Payment> findByStatusAndReviewRulingIsNullAndHeldAtBefore(
            PaymentStatus status, Instant heldBefore);

    /** What is waiting for a person, oldest first. */
    List<Payment> findByMerchantIdAndStatusAndReviewRulingIsNullOrderByHeldAtAsc(
            UUID merchantId, PaymentStatus status);

    /** What needs a person and nobody has dealt with, oldest first. */
    List<Payment> findByNeedsAttentionSinceIsNotNullAndAttentionHandledAtIsNullOrderByNeedsAttentionSinceAsc();
}
