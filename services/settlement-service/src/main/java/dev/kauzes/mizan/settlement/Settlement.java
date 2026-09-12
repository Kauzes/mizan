package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.money.Money;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing a day: turning everything captured into one batch per merchant per currency.
 *
 * <p>Per currency as well as per merchant, because a batch is a total and a total in two
 * currencies is not a total. Adding lira to yen produces a number with no meaning that
 * somebody would then be paid.
 *
 * <p>Closing is repeatable, which is the property the whole design turns on. A batch claims
 * its payments by writing its id onto them in the same transaction that creates it, and a
 * second close for a day that already has a batch answers with that batch and claims nothing.
 * A payment belongs to exactly one batch forever, and a batch a merchant has been shown never
 * changes afterwards.
 *
 * <p>Which is why a close claims everything captured <em>up to and including</em> its day
 * rather than only on it. Captures arrive as events, so one can turn up after its day has
 * been settled — and it has to be paid rather than stranded. It goes into the next batch, and
 * keeps its own capture date on the row so reconciliation can still find it under the day the
 * bank will name it by.
 */
@Component
public class Settlement {

    private static final Logger log = LoggerFactory.getLogger(Settlement.class);

    private final JdbcTemplate jdbc;
    private final Fees fees;

    public Settlement(JdbcTemplate jdbc, Fees fees) {
        this.jdbc = jdbc;
        this.fees = fees;
    }

    /**
     * Closes one day, for every merchant and currency that has anything waiting.
     *
     * <p>Each batch in its own transaction. One merchant's arithmetic going wrong should not
     * leave every other merchant unsettled, and a day's close that is all-or-nothing is a
     * day's close that nobody dares run.
     */
    public List<Closed> closeDay(LocalDate day) {
        List<Map<String, Object>> groups = jdbc.queryForList(
                """
                select merchant_id, currency, count(*) as payments
                from settleable
                where settled_for <= ? and batch_id is null
                group by merchant_id, currency
                order by merchant_id, currency
                """,
                day);

        List<Closed> closed = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            UUID merchantId = (UUID) group.get("merchant_id");
            String currency = (String) group.get("currency");
            try {
                closed.add(close(merchantId, day, currency));
            } catch (RuntimeException failed) {
                log.error(
                        "could not close {} for merchant {} in {}",
                        day,
                        merchantId,
                        currency,
                        failed);
            }
        }

        if (!closed.isEmpty()) {
            log.info("closed {} into {} batch(es)", day, closed.size());
        }
        return closed;
    }

    /**
     * One batch: what was captured, what it cost, and what is owed.
     *
     * <p>The row order matters and is fixed by the query. The fees come back from
     * {@link Fees} in the same order as the amounts went in, and attributing them to the
     * wrong payments would still add up — which is exactly the kind of bug that survives a
     * test of the totals.
     */
    @Transactional
    public Closed close(UUID merchantId, LocalDate day, String currencyCode) {
        // Asked first, so that a second close answers with the batch that exists rather than
        // trying to write another one. A batch a merchant has already been shown must not
        // change, so a capture that arrives after its day was settled waits for the next
        // close rather than being added to it.
        java.util.Optional<Closed> alreadyClosed = existing(merchantId, day, currencyCode);
        if (alreadyClosed.isPresent()) {
            return alreadyClosed.get();
        }

        List<Map<String, Object>> waiting = jdbc.queryForList(
                """
                select payment_id, amount
                from settleable
                where merchant_id = ? and settled_for <= ? and currency = ? and batch_id is null
                order by captured_at, payment_id
                """,
                merchantId,
                day,
                currencyCode);

        if (waiting.isEmpty()) {
            throw new IllegalStateException(
                    "nothing to settle for " + merchantId + " on " + day);
        }

        Currency currency = Currency.getInstance(currencyCode);
        List<Long> amounts = waiting.stream()
                .map(row -> ((Number) row.get("amount")).longValue())
                .toList();

        Fees.Charged charged = fees.charge(amounts, currency);
        UUID batchId = UUID.randomUUID();

        try {
            jdbc.update(
                    """
                    insert into settlement_batch (id, merchant_id, settled_for, currency,
                        captured, fee, net, payments, fee_basis_points,
                        fee_fixed_per_payment, closed_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    batchId,
                    merchantId,
                    day,
                    currencyCode,
                    charged.captured().amount(),
                    charged.fee().amount(),
                    charged.net().amount(),
                    amounts.size(),
                    fees.basisPoints(),
                    fees.fixedPerPayment(),
                    Timestamp.from(Instant.now()));

        } catch (DuplicateKeyException raced) {
            // Two closes running at once. One of them wrote the batch; this one rolls back,
            // because the alternative is a second batch for the same day and a merchant paid
            // twice for it. The caller's retry finds the batch that won.
            log.info("{} was closed for merchant {} while this close was running", day, merchantId);
            throw raced;
        }

        for (int index = 0; index < waiting.size(); index++) {
            jdbc.update(
                    "update settleable set batch_id = ?, fee = ? where payment_id = ?",
                    batchId,
                    charged.perPayment().get(index),
                    waiting.get(index).get("payment_id"));
        }

        log.info(
                "settled {} payment(s) for merchant {} on {}: {} captured, {} fee, {} owed",
                amounts.size(),
                merchantId,
                day,
                charged.captured(),
                charged.fee(),
                charged.net());

        return new Closed(
                batchId,
                merchantId,
                day,
                charged.captured(),
                charged.fee(),
                charged.net(),
                amounts.size());
    }

    private java.util.Optional<Closed> existing(
            UUID merchantId, LocalDate day, String currencyCode) {

        return jdbc
                .query(
                        """
                        select id, captured, fee, net, payments, currency
                        from settlement_batch
                        where merchant_id = ? and settled_for = ? and currency = ?
                        """,
                        (row, index) -> {
                            Currency currency = Currency.getInstance(row.getString("currency"));
                            return new Closed(
                                    row.getObject("id", UUID.class),
                                    merchantId,
                                    day,
                                    Money.of(row.getLong("captured"), currency),
                                    Money.of(row.getLong("fee"), currency),
                                    Money.of(row.getLong("net"), currency),
                                    row.getInt("payments"));
                        },
                        merchantId,
                        day,
                        currencyCode)
                .stream()
                .findFirst();
    }

    /** A merchant's batches, most recently settled first. */
    public List<Map<String, Object>> forMerchant(UUID merchantId, int limit) {
        return jdbc.queryForList(
                """
                select id, settled_for, currency, captured, fee, net, payments,
                       fee_basis_points, fee_fixed_per_payment, closed_at
                from settlement_batch
                where merchant_id = ?
                order by settled_for desc, currency
                limit ?
                """,
                merchantId,
                limit);
    }

    /** What made up one batch, and what each payment in it contributed to the fee. */
    public List<Map<String, Object>> itemsOf(UUID merchantId, UUID batchId) {
        return jdbc.queryForList(
                """
                select s.payment_id, s.amount, s.fee, s.captured_at, s.acquirer_reference
                from settleable s
                join settlement_batch b on b.id = s.batch_id
                where b.id = ? and b.merchant_id = ?
                order by s.captured_at, s.payment_id
                """,
                batchId,
                merchantId);
    }

    /** What a merchant is owed across every batch, and what has been paid. */
    public List<Map<String, Object>> owedTo(UUID merchantId) {
        return jdbc.queryForList(
                """
                select currency, sum(captured) as captured, sum(fee) as fee, sum(net) as net,
                       sum(payments) as payments, count(*) as batches
                from settlement_batch
                where merchant_id = ?
                group by currency
                order by currency
                """,
                merchantId);
    }

    /** A day closed, as the thing that closed it sees it. */
    public record Closed(
            UUID batchId,
            UUID merchantId,
            LocalDate day,
            Money captured,
            Money fee,
            Money net,
            int payments) {

        public Map<String, Object> asAnswer() {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("batchId", batchId);
            answer.put("merchantId", merchantId);
            answer.put("settledFor", day.toString());
            answer.put("currency", captured.currency().getCurrencyCode());
            answer.put("captured", captured.amount());
            answer.put("fee", fee.amount());
            answer.put("net", net.amount());
            answer.put("payments", payments);
            return answer;
        }
    }
}
