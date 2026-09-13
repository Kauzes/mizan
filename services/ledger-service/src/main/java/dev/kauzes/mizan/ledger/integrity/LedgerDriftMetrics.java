package dev.kauzes.mizan.ledger.integrity;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ledger drift, as a number rather than as a question somebody has to think to ask.
 *
 * <p>The integrity check has been answerable since MIZ-38 and asserted in CI since MIZ-74, and
 * both of those require somebody or something to go and ask. This runs it on a timer and
 * publishes what it found, so that "the books do not balance" can wake a person up rather than
 * waiting to be discovered. MIZ-80 is the rule that does the waking.
 *
 * <p>Four numbers rather than one, because they are the check's own three questions plus the
 * answer: which one is not zero says where the bug is. A single "unsound" flag would tell
 * somebody the ledger is broken and nothing about where to look.
 *
 * <p>The cost is watched rather than assumed. The check is three sequential scans, measured at
 * 6ms over this platform's data and under a second at a thousand times that, and how long it
 * took is published alongside what it found: a check whose cost nobody watches is a check that
 * gets switched off the first time somebody notices it.
 */
@Component
public class LedgerDriftMetrics {

    private static final Logger log = LoggerFactory.getLogger(LedgerDriftMetrics.class);

    private final LedgerIntegrityService integrity;

    private final AtomicLong sound = new AtomicLong(1);
    private final AtomicLong unbalancedEntries = new AtomicLong();
    private final AtomicLong driftedAccounts = new AtomicLong();
    private final AtomicLong currenciesOutOfBalance = new AtomicLong();
    private final AtomicLong tookMillis = new AtomicLong();
    private final AtomicLong postingsChecked = new AtomicLong();

    public LedgerDriftMetrics(MeterRegistry meters, LedgerIntegrityService integrity) {
        this.integrity = integrity;

        Gauge.builder("mizan.ledger.sound", sound, AtomicLong::get)
                .description("1 while every entry, every account and every currency agrees")
                .register(meters);

        Gauge.builder("mizan.ledger.entries.unbalanced", unbalancedEntries, AtomicLong::get)
                .description("Entries whose own postings do not sum to zero")
                .register(meters);

        Gauge.builder("mizan.ledger.accounts.drifted", driftedAccounts, AtomicLong::get)
                .description("Accounts whose kept balance disagrees with their postings")
                .register(meters);

        Gauge.builder("mizan.ledger.currencies.out.of.balance", currenciesOutOfBalance,
                        AtomicLong::get)
                .description("Currencies whose postings do not sum to zero platform wide")
                .register(meters);

        Gauge.builder("mizan.ledger.check.millis", tookMillis, AtomicLong::get)
                .description("How long the last integrity check took")
                .register(meters);

        Gauge.builder("mizan.ledger.postings.checked", postingsChecked, AtomicLong::get)
                .description("How many postings the last check read, so sound is never a "
                        + "statement about nothing")
                .register(meters);
    }

    @Scheduled(
            fixedDelayString = "${mizan.integrity.check-every:1m}",
            initialDelayString = "${mizan.integrity.check-first-after:30s}")
    public void check() {
        IntegrityReport report = integrity.check();

        sound.set(report.sound() ? 1 : 0);
        unbalancedEntries.set(report.entries().size());
        driftedAccounts.set(report.drifted().size());
        currenciesOutOfBalance.set(
                report.totals().stream().filter(total -> !total.balances()).count());
        tookMillis.set(report.tookMillis());
        postingsChecked.set(report.examined().postings());

        if (!report.sound()) {
            // The service itself already logs the detail. This says it again where the number
            // came from, because a gauge that changed with nothing beside it in the log is a
            // gauge somebody will distrust before they act on it.
            log.error("the ledger does not balance: {}", report.summary());
        }
    }
}
