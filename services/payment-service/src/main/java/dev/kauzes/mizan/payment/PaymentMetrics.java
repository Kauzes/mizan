package dev.kauzes.mizan.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What this service measures about payments, as opposed to about itself.
 *
 * <p>Heap size tells somebody the service is unwell. It never tells them a merchant is not
 * being paid. These are the numbers that say what is happening to the money: how much is being
 * authorized, what the bank is refusing and for what reason, and how much is sitting waiting
 * for a person.
 *
 * <p><b>Cardinality is the trap.</b> Every label multiplies the number of series a monitoring
 * system has to keep, so a merchant id or a payment id as a label is how that system falls over
 * — at exactly the moment somebody needs it. Nothing here is tagged with either. What belongs
 * in a metric is a small closed set of values; which merchant and which payment belong in a
 * trace or a log, where one row is one event rather than a dimension.
 *
 * <p>Amounts are deliberately absent too. A count of declines by reason is a fact about the
 * platform; a sum of money tagged by anything is one merchant's business sitting in a system
 * with no access control on it.
 */
@Component
public class PaymentMetrics {

    /** What the platform counts an authorization attempt as having ended in. */
    static final String OUTCOME = "outcome";

    /** Why the bank refused, for a decline, and {@code none} for everything else. */
    static final String REASON = "reason";

    /**
     * The reasons this platform is prepared to name.
     *
     * <p>An acquirer can put anything in that field, and a metric that accepted whatever
     * arrived would let one of them decide how many series this platform keeps. Anything else
     * counts as {@code other}, which is not information lost: the reason is on the payment and
     * in the log, where it belongs.
     */
    private static final Set<String> NAMEABLE = Set.of(
            "insufficient_funds",
            "do_not_honour",
            "do_not_honor",
            "stolen_card",
            "lost_card",
            "expired_card",
            "invalid_card",
            "suspected_fraud",
            "limit_exceeded");

    private static final String OTHER = "other";
    private static final String NONE = "none";

    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;

    /**
     * Read on a timer rather than when the scrape asks.
     *
     * <p>A gauge that queries the database when Prometheus asks puts a database query on the
     * scrape's thread, which means a slow database makes monitoring slow at the moment
     * monitoring is the thing being relied on.
     */
    private final AtomicLong needingAPerson = new AtomicLong();

    private final AtomicLong waitingForReview = new AtomicLong();

    public PaymentMetrics(MeterRegistry meters, JdbcTemplate jdbc) {
        this.meters = meters;
        this.jdbc = jdbc;

        Gauge.builder("mizan.payments.needing.a.person", needingAPerson, AtomicLong::get)
                .description("Payments and refunds that no amount of retrying will move")
                .register(meters);

        Gauge.builder("mizan.reviews.waiting", waitingForReview, AtomicLong::get)
                .description("Payments held for review that nobody has ruled on")
                .register(meters);
    }

    /** The bank approved it. */
    public void authorized() {
        count("approved", NONE);
    }

    /** The bank refused it, and said why. */
    public void declined(String reason) {
        count("declined", nameable(reason));
    }

    /**
     * Risk held it, so the bank was never asked.
     *
     * <p>Counted as an outcome of its own rather than as a decline. They are different facts
     * with different people to talk to: a decline is between a merchant and a bank, and a hold
     * is this platform's own decision, which somebody here has to answer for.
     */
    public void heldForReview() {
        count("held", NONE);
    }

    /** The bank did not answer in time, and what it decided is not yet known. */
    public void noAnswer() {
        count("unknown", NONE);
    }

    private void count(String outcome, String reason) {
        Counter.builder("mizan.payments.authorizations")
                .description("Authorization attempts, by what became of them")
                .tag(OUTCOME, outcome)
                .tag(REASON, reason)
                .register(meters)
                .increment();
    }

    private static String nameable(String reason) {
        if (reason == null || reason.isBlank()) {
            return NONE;
        }
        String named = reason.trim().toLowerCase(Locale.ROOT);
        return NAMEABLE.contains(named) ? named : OTHER;
    }

    /**
     * Counts what is waiting for a person.
     *
     * <p>Two queries on a timer. Both are questions about the whole service rather than about
     * one merchant, which is why they are here and not on a repository: what is waiting is an
     * operator's number, and no merchant should be able to ask it.
     */
    @Scheduled(
            fixedDelayString = "${mizan.metrics.count-every:15s}",
            initialDelayString = "${mizan.metrics.count-first-after:10s}")
    public void countWhatIsWaiting() {
        needingAPerson.set(countOf(
                """
                select count(*) from payment
                where needs_attention_since is not null and attention_handled_at is null
                """)
                + countOf(
                        """
                        select count(*) from refund
                        where status = 'ABANDONED' and attention_handled_at is null
                        """));

        waitingForReview.set(countOf(
                """
                select count(*) from payment
                where status = 'HELD_FOR_REVIEW' and review_ruling is null
                """));
    }

    private long countOf(String sql) {
        Long counted = jdbc.queryForObject(sql, Long.class);
        return counted == null ? 0L : counted;
    }
}
