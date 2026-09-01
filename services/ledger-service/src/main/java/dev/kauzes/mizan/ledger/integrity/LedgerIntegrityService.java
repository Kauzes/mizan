package dev.kauzes.mizan.ledger.integrity;

import dev.kauzes.mizan.ledger.integrity.IntegrityReport.CurrencyTotal;
import dev.kauzes.mizan.ledger.integrity.IntegrityReport.Drifted;
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
 * and asks two questions that have to be true of any ledger, whatever wrote it.
 *
 * <p>It takes no locks and blocks no writes. It does read at repeatable read, because the two
 * questions are asked in two statements: at read committed each would see its own moment, and
 * an entry committed between them would look like drift that is not there. A check that cries
 * wolf is worse than no check.
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
        List<CurrencyTotal> totals = jdbc.query(
                TOTAL_PER_CURRENCY,
                (row, index) -> new CurrencyTotal(
                        row.getString("currency"),
                        row.getLong("total"),
                        row.getLong("accounts"),
                        row.getLong("postings")));

        List<Drifted> drifted = jdbc.query(
                ACCOUNTS_THAT_DISAGREE,
                (row, index) -> Drifted.of(
                        row.getObject("id", UUID.class),
                        row.getObject("merchant_id", UUID.class),
                        row.getString("code"),
                        row.getString("currency"),
                        row.getLong("kept"),
                        row.getLong("postings_total")));

        IntegrityReport report = IntegrityReport.of(Instant.now(), totals, drifted);

        if (report.sound()) {
            log.debug("{}", report.summary());
        } else {
            log.error("{}", report.summary());
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
}
