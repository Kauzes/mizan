package dev.kauzes.mizan.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drift as a number, so that the books not balancing can wake somebody up.
 *
 * <p>The integrity check has been answerable for several stories and asserted in CI since
 * MIZ-74, and both of those need somebody or something to go and ask. What is checked here is
 * the gauge: that it says sound while the books are sound, that it stops saying so the moment
 * they are not, and that which of the three numbers moved says where the bug is.
 *
 * <p>Which means breaking the ledger on purpose. A check nobody has watched fail is a check
 * nobody knows works.
 */
@SpringBootTest(properties = {
    // Run by hand. A scheduler that fired halfway through would make these assertions about
    // timing rather than about drift.
    "mizan.integrity.check-first-after=3650d"
})
class LedgerDriftMetricsTest extends MizanIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private LedgerDriftMetrics drift;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void saysSoundWhileTheBooksAreSound() {
        drift.check();

        assertThat(gauge("mizan.ledger.sound")).isEqualTo(1);
        assertThat(gauge("mizan.ledger.entries.unbalanced")).isZero();
        assertThat(gauge("mizan.ledger.accounts.drifted")).isZero();
        assertThat(gauge("mizan.ledger.currencies.out.of.balance")).isZero();
    }

    @Test
    void saysHowMuchItLookedAtAndHowLongItTook() {
        anEntryThatBalances();

        drift.check();

        // Every one of the three questions passes trivially over empty tables, so a number
        // saying "sound" is worth nothing without a number saying what was read.
        assertThat(gauge("mizan.ledger.postings.checked")).isPositive();
        // And what it cost, because a check whose cost nobody watches is a check that gets
        // switched off the first time somebody notices it.
        assertThat(gauge("mizan.ledger.check.millis")).isNotNegative();
    }

    @Test
    void stopsSayingSoundTheMomentABalanceIsEdited() {
        UUID account = anAccount();

        jdbc.update("update account set balance = balance + 999 where id = ?", account);
        try {
            drift.check();

            assertThat(gauge("mizan.ledger.sound")).isZero();
            // And it is the account question that moved, not the other two. Which number is
            // not zero is what tells somebody where to look.
            assertThat(gauge("mizan.ledger.accounts.drifted")).isEqualTo(1);
            assertThat(gauge("mizan.ledger.entries.unbalanced")).isZero();
        } finally {
            jdbc.update("update account set balance = balance - 999 where id = ?", account);
        }

        drift.check();
        assertThat(gauge("mizan.ledger.sound")).as("and sound again once repaired").isEqualTo(1);
    }

    /**
     * One ordinary entry, written the way the database insists on: both postings in one
     * transaction, because the balance check is deferred to the commit and an entry is never
     * half written.
     */
    private void anEntryThatBalances() {
        UUID debit = anAccount();
        UUID credit = anAccount();
        UUID entry = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "insert into journal_entry (id, merchant_id, external_reference, "
                            + "request_fingerprint, description, occurred_at, recorded_at) "
                            + "values (?, ?, ?, 'counted', 'Something to count', now(), now())",
                    entry,
                    UUID.randomUUID(),
                    "counted:" + entry);
            jdbc.update(
                    "insert into posting (id, entry_id, account_id, amount) values (?, ?, ?, 100)",
                    UUID.randomUUID(),
                    entry,
                    debit);
            jdbc.update(
                    "insert into posting (id, entry_id, account_id, amount) values (?, ?, ?, -100)",
                    UUID.randomUUID(),
                    entry,
                    credit);
            jdbc.update("update account set balance = 100 where id = ?", debit);
            jdbc.update("update account set balance = -100 where id = ?", credit);
        });
    }

    private double gauge(String name) {
        return meters.find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    /**
     * An account of this test's own, so that editing its balance breaks nothing anybody else
     * is looking at. Its postings are none, which is a balance of zero, which is exactly what
     * makes adding 999 to it a drift.
     */
    private UUID anAccount() {
        UUID account = UUID.randomUUID();
        jdbc.update(
                """
                insert into account (id, merchant_id, code, name, type, currency, balance,
                    version, created_at)
                values (?, ?, ?, 'Counting drift', 'ASSET', 'TRY', 0, 0, now())
                """,
                account,
                UUID.randomUUID(),
                "drift.metrics." + account);
        return account;
    }
}
