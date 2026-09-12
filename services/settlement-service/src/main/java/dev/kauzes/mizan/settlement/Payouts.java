package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Taking the fee, and paying the merchant.
 *
 * <p>Both are entries in the ledger and neither adjusts a balance. What makes that safe rather
 * than merely correct is the ordering: the entry is posted first, and only then is this
 * service's own record written. The ledger is idempotent on the entry's reference, so a crash
 * between the two leaves an entry that the next attempt is answered with — whereas recording
 * first and posting second would leave a batch that looks paid with nothing in the books to
 * say it was.
 *
 * <p>The same lesson as MIZ-33 and MIZ-36, and the same shape as a capture: contact the thing
 * that cannot be undone first, write the local note second, and make the first step repeatable.
 */
@Component
public class Payouts {

    private static final Logger log = LoggerFactory.getLogger(Payouts.class);

    private final JdbcTemplate jdbc;
    private final LedgerBooks books;
    private final TransactionTemplate transactions;

    public Payouts(JdbcTemplate jdbc, LedgerBooks books, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.books = books;
        this.transactions = transactions;
    }

    /**
     * Puts a closed batch's fee in the books, if it is not there already.
     *
     * <p>Separate from closing the batch, because closing is a decision about a set of
     * payments and this is a movement of money. A ledger that is briefly unreachable should
     * not stop a day being settled: the batch exists, the fee follows, and the sweep below
     * finishes anything left over.
     */
    public boolean recordFeeFor(UUID batchId) {
        Map<String, Object> batch = batch(batchId);
        if (batch.get("fee_entry_id") != null) {
            return false;
        }

        long fee = ((Number) batch.get("fee")).longValue();
        if (fee == 0) {
            // A batch with no fee has nothing to record. Marked as recorded anyway, or the
            // sweep would pick it up forever looking for a movement that does not exist.
            transactions.executeWithoutResult(status -> jdbc.update(
                    "update settlement_batch set fee_entry_id = ?, fee_recorded_at = ? "
                            + "where id = ? and fee_entry_id is null",
                    batchId,
                    Timestamp.from(Instant.now()),
                    batchId));
            return true;
        }

        UUID entry = books.recordFee(
                (UUID) batch.get("merchant_id"), batchId, fee, (String) batch.get("currency"));

        // In a transaction of its own, deliberately: the entry is already written, and the
        // whole point of the ordering is that this step can be retried without doubt.
        transactions.executeWithoutResult(status -> jdbc.update(
                "update settlement_batch set fee_entry_id = ?, fee_recorded_at = ? "
                        + "where id = ? and fee_entry_id is null",
                entry,
                Timestamp.from(Instant.now()),
                batchId));

        log.info("recorded the fee on batch {} as entry {}", batchId, entry);
        return true;
    }

    /**
     * Pays a merchant what a batch left them.
     *
     * <p>Idempotent against the batch: asking twice pays once, and the second answer is the
     * payout that already happened rather than a refusal that hides which of the two it was.
     *
     * <p>Refuses until the fee is in the books. Paying the net before recording the fee would
     * leave the merchant's settlement balance holding a fee nobody had earned, and the two
     * halves of a settlement would be visible in the wrong order to anybody reading the books
     * at that moment.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String, Object> pay(UUID batchId) {
        Map<String, Object> batch = batch(batchId);

        if (batch.get("paid_at") != null) {
            return answer(batch, false);
        }
        if (batch.get("fee_entry_id") == null) {
            recordFeeFor(batchId);
            batch = batch(batchId);
        }

        long net = ((Number) batch.get("net")).longValue();
        if (net == 0) {
            throw new UnprocessableException(
                    "This batch left nothing to pay: the fee came to everything captured.");
        }

        // What the books say is owed, which is not the same as what this batch came to. A
        // refund since the day closed has reduced it, and a settlement service that added up
        // only its own rows would confidently pay out money that had already gone back to a
        // customer.
        //
        // A guard rather than an invariant, said plainly: two payouts for two batches of the
        // same merchant could both pass this check at once and together exceed what is owed.
        // What that leaves is an overpayment written down as two entries that still balance,
        // visible to the integrity check and to the merchant, rather than a hidden one — and
        // making it impossible belongs in the ledger, as a rule about an account rather than
        // a rule about a caller.
        long owed = books.owedTo(
                (UUID) batch.get("merchant_id"), (String) batch.get("currency"));

        if (net > owed) {
            throw new UnprocessableException(
                    "This batch came to "
                            + net
                            + " but the books say only "
                            + owed
                            + " is owed to this merchant, so paying it would be paying them "
                            + "somebody else's money. Money has gone back to a customer since "
                            + "this day closed; somebody has to decide what to pay.");
        }

        UUID entry = books.recordPayout(
                (UUID) batch.get("merchant_id"), batchId, net, (String) batch.get("currency"));

        int paid = jdbc.update(
                "update settlement_batch set payout_entry_id = ?, paid_at = ? "
                        + "where id = ? and paid_at is null",
                entry,
                Timestamp.from(Instant.now()),
                batchId);

        if (paid == 0) {
            // Somebody else paid it while this was in flight. The ledger answered both with
            // the same entry, because the reference is the same, so nothing was paid twice
            // and the winner's record stands.
            log.info("batch {} was paid while this payout was running", batchId);
            return answer(batch(batchId), false);
        }

        log.info("paid out batch {} as entry {}", batchId, entry);
        return answer(batch(batchId), true);
    }

    /**
     * Finishes what is unfinished: any closed batch whose fee is not yet in the books.
     *
     * <p>Every such batch, not only the most recent. A ledger that was unreachable for an hour
     * would otherwise leave an hour of batches uncharged forever, and nobody would notice
     * until the platform's revenue did not add up.
     */
    public int recordWhatIsNotYetInTheBooks() {
        List<UUID> unrecorded = jdbc.queryForList(
                "select id from settlement_batch where fee_entry_id is null order by closed_at",
                UUID.class);

        int recorded = 0;
        for (UUID batchId : unrecorded) {
            try {
                if (recordFeeFor(batchId)) {
                    recorded++;
                }
            } catch (RuntimeException notYet) {
                // Left for the next pass. Said at warning because a fee that never reaches
                // the books is revenue this platform cannot reconcile, which is worth
                // noticing even though it is recoverable.
                log.warn("the fee on batch {} is not in the books yet: {}",
                        batchId, notYet.getMessage());
            }
        }
        return recorded;
    }

    /** What has been settled and not yet paid, oldest first, which is the queue to work. */
    public List<Map<String, Object>> unpaid(int limit) {
        return jdbc.queryForList(
                """
                select id, merchant_id, settled_for, currency, captured, fee, net, payments,
                       fee_entry_id, closed_at
                from settlement_batch
                where paid_at is null
                order by settled_for, merchant_id
                limit ?
                """,
                limit);
    }

    private Map<String, Object> batch(UUID batchId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                """
                select id, merchant_id, settled_for, currency, captured, fee, net, payments,
                       fee_entry_id, payout_entry_id, paid_at
                from settlement_batch where id = ?
                """,
                batchId);

        if (found.isEmpty()) {
            throw new NotFoundException("There is no settlement batch with that id.");
        }
        return found.getFirst();
    }

    private static Map<String, Object> answer(Map<String, Object> batch, boolean nowPaid) {
        Map<String, Object> answer = new LinkedHashMap<>(batch);
        // Said out loud, because "paid" and "was already paid" are different things to an
        // operator wondering whether they double-clicked.
        answer.put("paidNow", nowPaid);
        return answer;
    }
}
