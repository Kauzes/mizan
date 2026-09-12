package dev.kauzes.mizan.ledger.integrity;

import dev.kauzes.mizan.ledger.integrity.IntegrityReport.CurrencyTotal;
import dev.kauzes.mizan.ledger.integrity.IntegrityReport.Drifted;
import dev.kauzes.mizan.ledger.integrity.IntegrityReport.Examined;
import dev.kauzes.mizan.ledger.integrity.IntegrityReport.Unbalanced;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks the ledger to prove it has not drifted.
 *
 * <p>Every other check in this epic is written by the same people as the code it checks, and
 * shares its assumptions: the tests know what the service intends and would agree with a bug
 * that was consistent about itself. This one assumes nothing. It reads what is in the tables
 * and asks three questions that have to be true of any ledger, whatever wrote it.
 *
 * <p>Three, not one, because they can disagree and which one fails says where the bug is.
 *
 * <ul>
 *   <li><b>Entry by entry.</b> Each entry's own postings sum to zero, per currency, and there
 *       are at least two of them. A writer that produced a bad entry fails here.
 *   <li><b>Account by account.</b> Each account's kept balance is what its postings add up to.
 *       The fast copy having quietly stopped matching the slow truth fails here.
 *   <li><b>Currency by currency.</b> Every posting in a currency, platform wide, sums to zero —
 *       including the clearing and revenue accounts no merchant ever sees. If every entry and
 *       every account is sound and this is not, money arrived from outside the system.
 * </ul>
 *
 * <p>It takes no locks and blocks no writes. It does read at repeatable read, because the
 * questions are asked in separate statements: at read committed each would see its own moment,
 * and an entry committed between them would look like drift that is not there. A check that
 * cries wolf is worse than no check.
 *
 * <p><b>What it costs.</b> Three aggregates over {@code posting}, each a sequential scan and a
 * hash aggregate, so it is linear in the number of postings. Measured rather than assumed,
 * against the local stack after a smoke run, with synthetic balanced entries loaded to make up
 * the difference:
 *
 * <pre>
 *     332 postings       6 ms
 *  33,532 postings      57 ms     (a hundred times)
 * 365,532 postings     949 ms     (a thousand times)
 * </pre>
 *
 * <p>Linear, as the plan says it should be, and still under a second at a thousand times this
 * platform's data. The report says how long it took, because a check whose cost nobody watches
 * is a check that gets switched off the first time somebody notices it.
 *
 * <p>What changes past that is where it runs and how often, not what it asks. In the order the
 * answers would be reached for: run it against a read replica, so the scan competes with
 * nothing; then scope it to a window — entries have a recorded date and postings have an entry,
 * so the same three questions over "since the last known-good checkpoint" are answerable with
 * the existing indexes; then keep a row per closed period holding the totals that were true at
 * the time, which turns the whole-history question into the sum of periods already checked plus
 * the open one. None of that is needed at this size, and writing it now would mean a cache to
 * keep correct inside the one service whose entire value is that it is the truth.
 */
@Service
public class LedgerIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityService.class);

    /**
     * Money is only ever moved from somewhere to somewhere, so the sum of every posting in a
     * currency is zero. Grouped by the account's currency, because a posting has none of its
     * own.
     */
    private static final String TOTAL_PER_CURRENCY =
            """
            select a.currency as currency,
                   coalesce(sum(p.amount), 0) as total,
                   count(distinct a.id) as accounts,
                   count(p.id) as postings
              from account a
              left join posting p on p.account_id = a.id
             group by a.currency
             order by a.currency
            """;

    /**
     * An entry that does not balance on its own, or has too few postings to be an entry.
     *
     * <p>Left joined from the entry rather than inner joined, so an entry with no postings at
     * all is found rather than silently dropped by the join — which is the shape the worst
     * corruption would take.
     */
    private static final String ENTRIES_THAT_DO_NOT_BALANCE =
            """
            select e.id as id,
                   e.merchant_id as merchant_id,
                   e.external_reference as reference,
                   a.currency as currency,
                   count(p.id) as postings,
                   coalesce(sum(p.amount), 0) as out_by
              from journal_entry e
              left join posting p on p.entry_id = e.id
              left join account a on a.id = p.account_id
             group by e.id, e.merchant_id, e.external_reference, a.currency
            having coalesce(sum(p.amount), 0) <> 0 or count(p.id) < 2
             order by e.id
            """;

    /**
     * An account whose kept balance is not what its own postings add up to. The fast copy
     * having quietly stopped matching the slow truth is exactly the failure MIZ-37 introduced
     * the possibility of.
     */
    private static final String ACCOUNTS_THAT_DISAGREE =
            """
            select a.id as id,
                   a.merchant_id as merchant_id,
                   a.code as code,
                   a.currency as currency,
                   a.balance as kept,
                   coalesce(sum(p.amount), 0) as postings_total
              from account a
              left join posting p on p.account_id = a.id
             group by a.id, a.merchant_id, a.code, a.currency, a.balance
            having a.balance <> coalesce(sum(p.amount), 0)
             order by a.code
            """;

    private final JdbcTemplate jdbc;

    public LedgerIntegrityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IntegrityReport check() {
        Instant started = Instant.now();

        List<CurrencyTotal> totals = jdbc.query(
                TOTAL_PER_CURRENCY,
                (row, index) -> new CurrencyTotal(
                        row.getString("currency"),
                        row.getLong("total"),
                        row.getLong("accounts"),
                        row.getLong("postings")));

        List<Unbalanced> entries = jdbc.query(
                ENTRIES_THAT_DO_NOT_BALANCE,
                (row, index) -> new Unbalanced(
                        row.getObject("id", UUID.class),
                        row.getObject("merchant_id", UUID.class),
                        row.getString("reference"),
                        row.getString("currency"),
                        row.getLong("postings"),
                        row.getLong("out_by")));

        List<Drifted> drifted = jdbc.query(
                ACCOUNTS_THAT_DISAGREE,
                (row, index) -> Drifted.of(
                        row.getObject("id", UUID.class),
                        row.getObject("merchant_id", UUID.class),
                        row.getString("code"),
                        row.getString("currency"),
                        row.getLong("kept"),
                        row.getLong("postings_total")));

        IntegrityReport report = IntegrityReport.of(
                Instant.now(),
                examined(totals),
                Duration.between(started, Instant.now()).toMillis(),
                totals,
                entries,
                drifted);

        if (report.sound()) {
            log.debug("{}", report.summary());
        } else {
            log.error("{}", report.summary());
            report.entries()
                    .forEach(entry -> log.error(
                            "entry {} ({}) has {} posting(s) in {} summing to {} rather than 0",
                            entry.reference(),
                            entry.entryId(),
                            entry.postings(),
                            entry.currency(),
                            entry.outBy()));
            report.drifted()
                    .forEach(account -> log.error(
                            "account {} ({}) holds {} but its postings sum to {}, out by {}",
                            account.code(),
                            account.accountId(),
                            account.keptBalance(),
                            account.postingsTotal(),
                            account.outBy()));
        }
        return report;
    }

    /**
     * What the answer is about.
     *
     * <p>The accounts and postings come from the totals already read rather than from counting
     * them again, so the numbers a caller is shown are the ones the check itself used. Entries
     * are counted, because nothing above needs a count of them and "sound" must never be a
     * statement about nothing.
     */
    private Examined examined(List<CurrencyTotal> totals) {
        Long entries = jdbc.queryForObject("select count(*) from journal_entry", Long.class);

        return new Examined(
                entries == null ? 0 : entries,
                totals.stream().mapToLong(CurrencyTotal::postings).sum(),
                totals.stream().mapToLong(CurrencyTotal::accounts).sum(),
                totals.size());
    }
}
