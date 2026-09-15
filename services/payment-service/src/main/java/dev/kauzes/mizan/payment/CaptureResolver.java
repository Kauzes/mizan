package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.NotFoundException;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes captures nobody finished. MIZ-90.
 *
 * <p>A capture takes the money at the acquirer and then records it in the ledger. Anything that
 * stops it between the two, a ledger that is down or a service killed mid-request, leaves money
 * taken and not recorded. The payment says authorized, which is true, and before this story that
 * was all it said: nothing marked a capture as begun, so only the merchant sending it again would
 * ever finish it.
 *
 * <p>Now the start is written down first, and this sweep looks for captures started and not
 * finished. It does not guess. It asks the acquirer where the authorization is now:
 *
 * <ul>
 *   <li><b>Captured:</b> the money was taken, so it is recorded in the ledger and the payment is
 *       captured, exactly as the original request would have done.
 *   <li><b>Still authorized:</b> the capture never reached the acquirer. Nothing moved, the mark is
 *       cleared, and the payment can be captured again.
 *   <li><b>Anything else</b>, voided or no record at all: not something to decide by rule. A person
 *       is asked, through the same queue every other stuck payment is in.
 * </ul>
 *
 * <p>If the acquirer cannot be asked, or the ledger still will not record it, the attempt is counted
 * and tried again next pass; after {@link #ATTEMPTS} the payment is a person's problem rather than
 * the sweep's.
 *
 * <p>Racing the original request, or another pod's sweep, is safe. The ledger entry is keyed on the
 * payment's id, and the version on the payment means only one writer marks it captured.
 */
@Component
public class CaptureResolver {

    private static final Logger log = LoggerFactory.getLogger(CaptureResolver.class);

    /** How many passes may fail to finish a capture before a person is asked. */
    static final int ATTEMPTS = 5;

    private final PaymentRepository payments;
    private final AcquirerClient acquirer;
    private final PaymentService paymentService;
    private final TransactionTemplate step;
    private final Duration settleFirst;

    public CaptureResolver(
            PaymentRepository payments,
            AcquirerClient acquirer,
            PaymentService paymentService,
            PlatformTransactionManager transactions,
            @Value("${mizan.captures.resolve-after:10s}") Duration settleFirst) {

        this.payments = payments;
        this.acquirer = acquirer;
        this.paymentService = paymentService;
        this.step = new TransactionTemplate(transactions);
        this.step.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.settleFirst = settleFirst;
    }

    /** Looks for captures started and never finished, that have settled, and finishes them. */
    @Scheduled(fixedDelayString = "${mizan.captures.resolve-every:15s}")
    public void finishWhatWasInterrupted() {
        Instant before = Instant.now().minus(settleFirst);
        List<Payment> interrupted =
                payments.findByStatusAndCaptureStartedAtBeforeAndNeedsAttentionSinceIsNull(
                        PaymentStatus.AUTHORIZED, before);

        if (interrupted.isEmpty()) {
            return;
        }

        log.info("finishing {} capture(s) that were started and never finished", interrupted.size());
        interrupted.forEach(payment -> finishQuietly(payment.merchantId(), payment.id()));
    }

    private void finishQuietly(UUID merchantId, UUID paymentId) {
        try {
            finish(merchantId, paymentId);
        } catch (OptimisticLockingFailureException somebodyElseGotThere) {
            log.debug("capture of payment {} was finished by somebody else", paymentId);
        } catch (MizanException notThisTime) {
            log.warn("could not finish the capture of payment {} this time: {}",
                    paymentId, notThisTime.getMessage());
        } catch (RuntimeException unexpected) {
            // One capture that cannot be finished must not stop the rest of the sweep.
            log.error("failed to finish the capture of payment {}", paymentId, unexpected);
        }
    }

    /**
     * Asks the acquirer about one interrupted capture, and does what its answer means.
     *
     * @return the payment as it now stands, whether or not anything changed
     */
    public PaymentRequests.PaymentResponse finish(UUID merchantId, UUID paymentId) {
        Payment payment = payments
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));
        if (payment.status() != PaymentStatus.AUTHORIZED || payment.captureStartedAt() == null) {
            // Finished already, by the original request arriving late or an earlier pass.
            return PaymentRequests.PaymentResponse.of(payment);
        }

        Optional<AcquirerClient.AcquirerDecision> atTheAcquirer;
        try {
            atTheAcquirer = acquirer.lookUp(paymentId);
        } catch (MizanException couldNotAsk) {
            attempted(merchantId, paymentId,
                    "A capture was started and the acquirer could not be asked whether it took the "
                            + "money: " + couldNotAsk.getMessage());
            throw couldNotAsk;
        }

        if (atTheAcquirer.isPresent() && atTheAcquirer.get().captured()) {
            try {
                PaymentRequests.PaymentResponse finished =
                        paymentService.finishCapture(merchantId, paymentId);
                log.info("payment {} was captured at the acquirer and is now recorded, after the "
                        + "capture was interrupted", paymentId);
                return finished;
            } catch (RuntimeException notRecorded) {
                attempted(merchantId, paymentId,
                        "The acquirer has taken the money and the ledger has not recorded it: "
                                + notRecorded.getMessage());
                throw notRecorded;
            }
        }

        if (atTheAcquirer.isPresent() && atTheAcquirer.get().stillOnlyAuthorized()) {
            return step.execute(status -> {
                Payment current = mine(merchantId, paymentId);
                if (current.status() == PaymentStatus.AUTHORIZED && current.captureStartedAt() != null) {
                    current.captureNotReached();
                    log.info("the capture of payment {} never reached the acquirer; it is "
                            + "authorized and can be captured again", paymentId);
                }
                return PaymentRequests.PaymentResponse.of(current);
            });
        }

        // Voided at the acquirer, or no record of the authorization at all. Either way what should
        // happen to this money is a judgement, and the sweep does not make judgements.
        String because = "A capture was started, and the acquirer now says "
                + atTheAcquirer.map(decision -> "this authorization is " + decision.state())
                        .orElse("it has no record of this payment")
                + ". Somebody needs to decide what happens to the money.";
        return step.execute(status -> {
            Payment current = mine(merchantId, paymentId);
            current.needsAPerson(because);
            log.error("NEEDS A PERSON: payment {} of merchant {}: {}", paymentId, merchantId, because);
            return PaymentRequests.PaymentResponse.of(current);
        });
    }

    /** Counts one pass that could not finish, and hands over to a person after enough of them. */
    private void attempted(UUID merchantId, UUID paymentId, String because) {
        step.executeWithoutResult(status -> {
            Payment current = mine(merchantId, paymentId);
            current.resolveAttempted();
            if (current.resolveAttempts() >= ATTEMPTS) {
                current.needsAPerson(because + " (after " + current.resolveAttempts() + " attempts)");
                log.error("NEEDS A PERSON: payment {} of merchant {}: {}",
                        paymentId, merchantId, current.attentionReason());
            }
        });
    }

    private Payment mine(UUID merchantId, UUID paymentId) {
        return payments
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));
    }
}
