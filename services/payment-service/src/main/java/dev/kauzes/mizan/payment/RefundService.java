package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.payment.PaymentRequests.RefundRequest;
import dev.kauzes.mizan.payment.PaymentRequests.RefundResponse;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Giving money back, in a way that survives being interrupted.
 *
 * <p>A refund is three steps across two other systems, and a process can die between any two
 * of them. Each of the three failures is silent, and each is expensive in a different way:
 *
 * <ul>
 *   <li>the acquirer gave the money back and the ledger never recorded it, so the books say
 *       the platform holds money it does not;
 *   <li>the ledger recorded it and the refund was never marked, so a retry is safe but nothing
 *       retries;
 *   <li>the acquirer was asked and did not answer, so nobody knows whether the money moved.
 * </ul>
 *
 * <p>So the refund writes down where it has got to <em>before</em> each step, in a transaction
 * of its own. A crash then leaves a record of what was attempted rather than nothing, and
 * {@link RefundResolver} finishes it from the step it reached. Never restarts it: the acquirer
 * must not be asked twice for money it has already returned, which is why every step is keyed
 * on the merchant's own reference and asking again is a question rather than a second refund.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AcquirerClient acquirer;
    private final LedgerClient ledger;
    private final PaymentEvents events;

    /**
     * Transactions are managed here rather than by an annotation because the point of this
     * class is that the steps commit separately. One boundary around all of it is exactly the
     * arrangement that loses the record of what was attempted.
     */
    private final TransactionTemplate transaction;

    public RefundService(
            PaymentRepository payments,
            RefundRepository refunds,
            AcquirerClient acquirer,
            LedgerClient ledger,
            PaymentEvents events,
            PlatformTransactionManager transactions) {

        this.payments = payments;
        this.refunds = refunds;
        this.acquirer = acquirer;
        this.ledger = ledger;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Asks for money to be given back, and gets as far as it can before answering. */
    public RefundResponse refund(UUID merchantId, UUID paymentId, RefundRequest request) {
        Refund refund = reserve(merchantId, paymentId, request);
        return RefundResponse.of(finish(refund.id()));
    }

    /**
     * Step one: reserve the amount and write down that a refund was asked for.
     *
     * <p>Committed on its own, before anybody outside is contacted. That ordering is the whole
     * story: the row has to exist before the acquirer is asked, or a process that dies during
     * the call leaves the money possibly gone and nothing at all saying so. The same lesson as
     * MIZ-33, MIZ-36 and MIZ-44, in the place where it costs the most.
     *
     * <p>The payment row is locked for the length of it, so two refunds arriving at once
     * cannot each read the same remaining amount and both conclude there is room.
     */
    private Refund reserve(UUID merchantId, UUID paymentId, RefundRequest request) {
        String reference = request.reference().trim();

        return transaction.execute(status -> {
            Payment payment = payments
                    .findForUpdateByIdAndMerchantId(paymentId, merchantId)
                    .orElseThrow(() -> new NotFoundException("No payment with that id."));

            // Inside the lock, so two copies of one retry cannot both find nothing.
            var already = refunds.findByPaymentIdAndReference(paymentId, reference);
            if (already.isPresent()) {
                log.info("refund {} of payment {} was already asked for", reference, paymentId);
                return already.get();
            }

            requireSameCurrency(payment, request);
            payment.refunded(request.amount());

            Refund refund = new Refund(payment, request.amount(), reference, request.reason());
            refunds.saveAndFlush(refund);
            return refund;
        });
    }

    /**
     * Steps two and three, from wherever this refund has got to.
     *
     * <p>Called by the request that asked for the refund and by the sweep that finds ones
     * nobody finished. Both go through here, so there is one path and it is the one that is
     * exercised on every ordinary refund rather than only when something has gone wrong.
     */
    Refund finish(UUID refundId) {
        Refund refund = refunds.findById(refundId).orElseThrow();

        if (refund.status() == RefundStatus.REQUESTED) {
            askTheAcquirer(refund);
            refund = refunds.findById(refundId).orElseThrow();
        }
        if (refund.status() == RefundStatus.RETURNED) {
            tellTheLedger(refund);
            refund = refunds.findById(refundId).orElseThrow();
        }
        return refund;
    }

    /**
     * Step two: ask the acquirer for the money back, and record that it went.
     *
     * <p>Asking again is safe and is how a lost answer is resolved. The call is keyed on the
     * merchant's own reference, so the acquirer answers a repeat with what it already did —
     * which makes "retry" and "ask what you did" the same request here, rather than two
     * endpoints where MIZ-44 needed both.
     */
    private void askTheAcquirer(Refund refund) {
        AcquirerClient.AcquirerRefund given;
        try {
            given = acquirer.refund(
                    acquirerReferenceFor(refund), refund.reference(), refund.amount());

        } catch (MizanException refused) {
            if (refused.errorCode() == ErrorCode.UNPROCESSABLE) {
                // It said no, which is a fact. Nothing moved, so the reservation goes back.
                giveBackTheReservation(refund, refused.getMessage());
                throw refused;
            }
            // It said nothing, which is a different fact. The money may be gone, so the
            // reservation stays and the sweep will ask again.
            couldNotFinish(refund, refused.getMessage());
            throw refused;
        }

        transaction.executeWithoutResult(status -> {
            Refund current = refunds.findById(refund.id()).orElseThrow();
            current.returned(given.acquirerReference());
        });
    }

    /**
     * Step three: tell the ledger, and only then say the refund is done.
     *
     * <p>The state never runs ahead of the books, exactly as a capture's does not. The entry's
     * reference is derived from the payment and the merchant's own reference, so writing it
     * twice writes one entry.
     */
    private void tellTheLedger(Refund refund) {
        Payment payment = payments.findById(refund.paymentId()).orElseThrow();

        UUID entry;
        try {
            entry = ledger.recordRefund(
                    refund.merchantId(), payment, refund.amount(), refund.reference());
        } catch (MizanException notRecorded) {
            couldNotFinish(refund, notRecorded.getMessage());
            throw notRecorded;
        }

        transaction.executeWithoutResult(status -> {
            Refund current = refunds.findById(refund.id()).orElseThrow();
            Payment fresh = payments.findById(refund.paymentId()).orElseThrow();
            current.recorded(entry);
            events.recordRefund(fresh, current);

            log.info(
                    "refunded {} of payment {} as {} and recorded it as entry {}",
                    current.amount(),
                    current.paymentId(),
                    current.acquirerReference(),
                    entry);
        });
    }

    /**
     * Records that an attempt did not work, in its own transaction so it survives the
     * exception that is about to be thrown at the caller.
     *
     * <p>Gives up after enough attempts rather than retrying forever. A refund nobody can
     * finish keeps its reservation and becomes a person's problem, because the money may have
     * gone back and releasing the amount would let it be refunded twice.
     */
    private void couldNotFinish(Refund refund, String why) {
        transaction.executeWithoutResult(status -> {
            Refund current = refunds.findById(refund.id()).orElseThrow();
            if (current.status().isFinished()) {
                return;
            }
            boolean enough = current.attemptFailed(why, RefundResolver.waitAfter(current), ATTEMPTS);
            if (enough) {
                current.abandoned(why);
                log.error(
                        "giving up on refund {} of payment {} after {} attempts: {}",
                        current.reference(),
                        current.paymentId(),
                        current.attempts(),
                        why);
            } else {
                log.warn(
                        "could not finish refund {} of payment {} this time: {}",
                        current.reference(),
                        current.paymentId(),
                        why);
            }
        });
    }

    /** How many times finishing a refund is attempted before a person is needed. */
    static final int ATTEMPTS = 5;

    /**
     * Releases the amount a refused refund had reserved.
     *
     * <p>Only ever for a refusal. A silence leaves the reservation alone, because the money may
     * already be gone and giving the merchant the headroom back would let them send it twice.
     */
    private void giveBackTheReservation(Refund refund, String why) {
        transaction.executeWithoutResult(status -> {
            Refund current = refunds.findById(refund.id()).orElseThrow();
            if (current.status() != RefundStatus.REQUESTED) {
                return;
            }
            Payment payment = payments
                    .findForUpdateByIdAndMerchantId(current.paymentId(), current.merchantId())
                    .orElseThrow();

            payment.refundReleased(current.amount());
            current.failed(why);

            log.info(
                    "the acquirer refused refund {} of payment {}, so its reservation is released",
                    current.reference(),
                    current.paymentId());
        });
    }

    /** The authorization the money was taken through, which is what a refund names. */
    private String acquirerReferenceFor(Refund refund) {
        return payments
                .findById(refund.paymentId())
                .map(Payment::acquirerReference)
                .orElseThrow(() -> new IllegalStateException(
                        "refund " + refund.id() + " has no payment"));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> list(UUID merchantId, UUID paymentId) {
        payments
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));

        return refunds.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(RefundResponse::of)
                .toList();
    }

    /**
     * A refund in a currency the payment was never in is a mistake, not a conversion.
     *
     * <p>Refusing it is the only safe answer: this platform has no rate, and inventing one to
     * be helpful is how a refund gives back a different amount of money than was taken.
     */
    private static void requireSameCurrency(Payment payment, RefundRequest request) {
        if (request.currency() == null) {
            return;
        }
        Currency asked;
        try {
            asked = Currency.getInstance(request.currency().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new UnprocessableException(
                    request.currency() + " is not a currency this platform knows.");
        }
        if (!asked.equals(payment.money().currency())) {
            throw new UnprocessableException(
                    "This payment was taken in "
                            + payment.money().currency().getCurrencyCode()
                            + " and can only be refunded in "
                            + payment.money().currency().getCurrencyCode()
                            + ".");
        }
    }
}
