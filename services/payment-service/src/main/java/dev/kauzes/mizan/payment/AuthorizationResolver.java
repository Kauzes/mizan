package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.MizanException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns not knowing into knowing, by asking.
 *
 * <p>A call that timed out has not failed. It has stopped telling us what happened, which is
 * a different thing: the acquirer may have reserved the money and lost the reply. Deciding
 * the payment failed is how a customer is charged for something the merchant believes never
 * happened; deciding it succeeded is how a merchant ships goods against money nobody reserved.
 *
 * <p>So neither is decided. The acquirer is asked what it did with the request, using the
 * payment's own id, and whatever it says is what the payment becomes. If it has no record,
 * nothing happened and the payment stays unresolved, which is visible and can be attempted
 * again.
 *
 * <p>This runs on a schedule so that a merchant does not have to notice. Running it twice, or
 * while the original call is still in flight, is safe: the answer is the same one, and the
 * version on the payment means only the first of two writers gets to record it.
 */
@Component
public class AuthorizationResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationResolver.class);

    private final PaymentRepository payments;
    private final AcquirerClient acquirer;
    private final TransactionTemplate transaction;
    private final Duration settleFirst;

    public AuthorizationResolver(
            PaymentRepository payments,
            AcquirerClient acquirer,
            PlatformTransactionManager transactions,
            @Value("${mizan.acquirer.resolve-after:10s}") Duration settleFirst) {

        this.payments = payments;
        this.acquirer = acquirer;
        this.transaction = new TransactionTemplate(transactions);
        this.settleFirst = settleFirst;
    }

    /**
     * Looks for payments nobody knows the outcome of, and asks about them.
     *
     * <p>Only ones that have been waiting a little while. A payment whose authorization
     * timed out a moment ago may still be being answered, and asking immediately would race
     * the call that is already in flight for no benefit.
     */
    @Scheduled(fixedDelayString = "${mizan.acquirer.resolve-every:15s}")
    public void resolveWhatIsUnknown() {
        Instant before = Instant.now().minus(settleFirst);
        List<Payment> waiting =
                payments.findByStatusAndUpdatedAtBefore(PaymentStatus.AUTHORIZATION_UNKNOWN, before);

        if (waiting.isEmpty()) {
            return;
        }

        log.info("asking the acquirer about {} payment(s) nobody knows the outcome of", waiting.size());
        waiting.forEach(payment -> resolveQuietly(payment.id(), payment.merchantId()));
    }

    /** One payment, with every reason it might not work today swallowed and logged. */
    private void resolveQuietly(UUID paymentId, UUID merchantId) {
        try {
            resolve(merchantId, paymentId);
        } catch (OptimisticLockingFailureException somebodyElseGotThere) {
            log.debug("payment {} was resolved by somebody else", paymentId);
        } catch (MizanException couldNotAsk) {
            log.warn("could not resolve payment {} this time: {}", paymentId, couldNotAsk.getMessage());
        } catch (RuntimeException unexpected) {
            // One payment that cannot be resolved must not stop the rest of the sweep.
            log.error("failed to resolve payment {}", paymentId, unexpected);
        }
    }

    /**
     * Asks about one payment and records what the acquirer says.
     *
     * @return the payment as it now stands, whether or not anything changed
     */
    public PaymentRequests.PaymentResponse resolve(UUID merchantId, UUID paymentId) {
        return transaction.execute(status -> {
            Payment payment = payments
                    .findByIdAndMerchantId(paymentId, merchantId)
                    .orElseThrow(() -> new dev.kauzes.mizan.common.error.NotFoundException(
                            "No payment with that id."));

            if (payment.status() != PaymentStatus.AUTHORIZATION_UNKNOWN) {
                // Already resolved, by an earlier sweep or by the original call arriving
                // late. Asking again would be harmless and answering again would not.
                return PaymentRequests.PaymentResponse.of(payment);
            }

            Optional<AcquirerClient.AcquirerDecision> decided = acquirer.lookUp(paymentId);

            if (decided.isEmpty()) {
                // A real answer: the acquirer never saw it, so nothing was reserved. The
                // payment stays unresolved rather than being called declined, because it was
                // not declined, and it can be attempted again.
                log.info("the acquirer has no record of payment {}; nothing was authorized",
                        paymentId);
                return PaymentRequests.PaymentResponse.of(payment);
            }

            AcquirerClient.AcquirerDecision decision = decided.get();
            if (decision.approved()) {
                payment.authorized(decision.acquirerReference(), decision.cardLastFour());
                log.info("payment {} turned out to be authorized as {}", paymentId,
                        decision.acquirerReference());
            } else {
                payment.declined(
                        decision.acquirerReference(), decision.cardLastFour(), decision.reason());
                log.info("payment {} turned out to be declined: {}", paymentId, decision.reason());
            }

            return PaymentRequests.PaymentResponse.of(payment);
        });
    }
}
