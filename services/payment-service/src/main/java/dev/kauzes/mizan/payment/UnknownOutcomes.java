package dev.kauzes.mizan.payment;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a payment's outcome is not known, in a transaction of its own.
 *
 * <p>This exists because of an ordering problem the platform has hit twice before. The
 * timeout is discovered inside the authorize transaction, and the way the caller is told is
 * by throwing, which rolls that transaction back. A note written there would be rolled back
 * with it, and the payment would go on saying nothing had been attempted while the acquirer
 * held the money. Committing it separately is what makes the record stick.
 */
@Component
class UnknownOutcomes {

    private static final Logger log = LoggerFactory.getLogger(UnknownOutcomes.class);

    private final PaymentRepository payments;

    UnknownOutcomes(PaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(UUID merchantId, UUID paymentId, String because) {
        payments.findByIdAndMerchantId(paymentId, merchantId).ifPresent(payment -> {
            if (payment.status().canMoveTo(PaymentStatus.AUTHORIZATION_UNKNOWN)) {
                payment.outcomeUnknown(because);
                log.warn("payment {} has an outcome nobody knows yet", paymentId);
            }
        });
    }
}
