package dev.kauzes.mizan.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes refunds nobody finished.
 *
 * <p>A refund that stopped halfway is not a refund that failed. It is one where the money may
 * be gone and the books do not say so, or where nobody knows whether it went at all. Both are
 * silent, both are found by a person reconciling by hand, and neither gets better by waiting.
 *
 * <p>So this picks them up and carries on <em>from the step they reached</em>. Not from the
 * beginning: the acquirer must not be asked twice for money it has already returned, which is
 * why the state is written down before each step rather than inferred afterwards.
 *
 * <p>Running this while the original request is still in flight is safe. Every step is keyed on
 * the merchant's own reference, so the acquirer answers a repeat with what it already did and
 * the ledger answers with the entry it already wrote; the version on the refund means only one
 * of two writers records the result.
 */
@Component
public class RefundResolver {

    private static final Logger log = LoggerFactory.getLogger(RefundResolver.class);

    private final RefundRepository refunds;
    private final RefundService refundService;
    private final Duration settleFirst;

    public RefundResolver(
            RefundRepository refunds,
            RefundService refundService,
            @Value("${mizan.refunds.resolve-after:10s}") Duration settleFirst) {

        this.refunds = refunds;
        this.refundService = refundService;
        this.settleFirst = settleFirst;
    }

    /**
     * Looks for refunds that stopped halfway, and finishes them.
     *
     * <p>Only ones that have been waiting a little while. A refund whose call is still in
     * flight would otherwise be raced for no benefit, and while racing is safe it is also
     * pointless.
     */
    @Scheduled(fixedDelayString = "${mizan.refunds.resolve-every:15s}")
    public void finishWhatWasInterrupted() {
        Instant before = Instant.now().minus(settleFirst);
        List<Refund> unfinished = refunds.findUnfinishedBefore(before, Instant.now());

        if (unfinished.isEmpty()) {
            return;
        }

        log.info("finishing {} refund(s) that nobody finished", unfinished.size());
        unfinished.forEach(this::finishQuietly);
    }

    private void finishQuietly(Refund refund) {
        try {
            Refund finished = refundService.finish(refund.id());
            if (finished.status() == RefundStatus.SUCCEEDED) {
                log.info(
                        "refund {} of payment {} was finished after being interrupted",
                        finished.reference(),
                        finished.paymentId());
            }
        } catch (RuntimeException notThisTime) {
            // Already recorded on the refund, with a growing delay before the next attempt.
            // One refund that will not finish must not stop the rest of the sweep.
            log.debug("refund {} still could not be finished", refund.id(), notThisTime);
        }
    }

    /**
     * How long a refund waits before the next attempt: longer each time, and never exactly as
     * long as whatever else is waiting.
     *
     * <p>Doubling, because a dependency that was unavailable a moment ago is likely to still
     * be, and jittered so that a hundred refunds knocked over by one outage do not all come
     * back at the same instant.
     */
    static Duration waitAfter(Refund refund) {
        long seconds = Math.min(300, 5L << Math.min(refund.attempts(), 6));
        long jitter = Math.max(1, seconds / 4);
        return Duration.ofSeconds(seconds + ThreadLocalRandom.current().nextLong(-jitter, jitter));
    }
}
