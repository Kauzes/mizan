package dev.kauzes.mizan.settlement;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comparing what this platform believes with what the bank says.
 *
 * <p>Four answers, named: matched, missing from the statement, extra on the statement, and
 * present on both for amounts that differ. A single "failed" count would hide the only
 * information worth having — a transaction the bank never saw and one it saw for the wrong
 * amount are different problems with different fixes.
 *
 * <p>And a fifth, which the four do not cover: a capture with no acquirer reference at all.
 * Nothing can be said about it. Calling it missing would send somebody looking for it in a
 * file where it could never appear, and calling it matched would be a lie.
 *
 * <p>Nothing here writes off a difference and nothing adjusts the books to agree with the
 * bank. Reconciliation reports; a job that could silently make the books match is a job that
 * could silently make them wrong. What to do about a difference is a person's decision, which
 * is MIZ-73.
 *
 * <p>Safe to re-run, and the same day reconciled twice says the same thing. Each run is its
 * own record, because it happened; the differences it finds are keyed by what they are about,
 * so a second run finds the rows the first one made rather than reporting every problem
 * again as new. That matters because the only time anybody reconciles twice is after an
 * incident, which is the worst time to be handed a page of duplicates.
 */
@Component
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final JdbcTemplate jdbc;
    private final AcquirerStatements statements;

    public Reconciliation(JdbcTemplate jdbc, AcquirerStatements statements) {
        this.jdbc = jdbc;
        this.statements = statements;
    }

    /** What one run found. */
    public record Found(
            UUID runId,
            LocalDate day,
            String currency,
            int statementRows,
            long statementTotal,
            int matched,
            int missingFromStatement,
            int extraOnStatement,
            int amountsDiffer,
            int unmatchable) {

        public Map<String, Object> asAnswer() {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("runId", runId);
            answer.put("day", day.toString());
            answer.put("currency", currency);
            answer.put("statementRows", statementRows);
            answer.put("statementTotal", statementTotal);
            answer.put("matched", matched);
            answer.put("missingFromStatement", missingFromStatement);
            answer.put("extraOnStatement", extraOnStatement);
            answer.put("amountsDiffer", amountsDiffer);
            answer.put("unmatchable", unmatchable);
            answer.put("differences", missingFromStatement + extraOnStatement + amountsDiffer
                    + unmatchable);
            return answer;
        }
    }

    @Transactional
    public Found reconcile(LocalDate day, String currency) {
        AcquirerStatements.Statement statement = statements.forDay(day, currency);

        // What this platform believes settled that day, by the reference the bank names it by.
        // Captures rather than batches: a batch is this platform's grouping and the bank has
        // never heard of it.
        List<Map<String, Object>> ours = jdbc.queryForList(
                """
                select payment_id, merchant_id, amount, acquirer_reference
                from settleable
                where settled_for = ? and currency = ?
                order by acquirer_reference
                """,
                day,
                currency);

        Map<String, Map<String, Object>> byReference = new LinkedHashMap<>();
        List<Map<String, Object>> unidentifiable = new ArrayList<>();
        for (Map<String, Object> mine : ours) {
            String reference = (String) mine.get("acquirer_reference");
            if (reference == null || reference.isBlank()) {
                // Nothing to match on. A capture the acquirer never gave a reference for is
                // one this platform cannot ask the bank about, which is its own kind of
                // problem and not a missing transaction.
                unidentifiable.add(mine);
            } else {
                byReference.put(reference, mine);
            }
        }

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        int matched = 0;
        List<Difference> differences = new ArrayList<>();
        Set<String> seenOnStatement = new LinkedHashSet<>();

        for (AcquirerStatements.Settled settled : statement.settled()) {
            seenOnStatement.add(settled.reference());
            Map<String, Object> mine = byReference.get(settled.reference());

            if (mine == null) {
                differences.add(Difference.extra(settled));
                continue;
            }

            long ourAmount = ((Number) mine.get("amount")).longValue();
            if (ourAmount == settled.amount()) {
                matched++;
            } else {
                differences.add(Difference.differing(settled, mine, ourAmount));
            }
        }

        for (Map.Entry<String, Map<String, Object>> mine : byReference.entrySet()) {
            if (!seenOnStatement.contains(mine.getKey())) {
                differences.add(Difference.missing(mine.getKey(), mine.getValue()));
            }
        }

        for (Map<String, Object> mine : unidentifiable) {
            differences.add(Difference.unmatchable(mine));
        }

        Found found = new Found(
                runId,
                day,
                currency,
                statement.rows(),
                statement.total(),
                matched,
                (int) differences.stream().filter(one -> one.is("MISSING_FROM_STATEMENT")).count(),
                (int) differences.stream().filter(one -> one.is("EXTRA_ON_STATEMENT")).count(),
                (int) differences.stream().filter(one -> one.is("AMOUNTS_DIFFER")).count(),
                (int) differences.stream().filter(one -> one.is("UNMATCHABLE")).count());

        record(found, now);
        for (Difference difference : differences) {
            remember(difference, day, currency, runId, now);
        }

        log.info(
                "reconciled {} in {}: {} matched, {} missing, {} extra, {} differing, {} "
                        + "unmatchable",
                day,
                currency,
                found.matched(),
                found.missingFromStatement(),
                found.extraOnStatement(),
                found.amountsDiffer(),
                found.unmatchable());

        return found;
    }

    private void record(Found found, Instant now) {
        jdbc.update(
                """
                insert into reconciliation_run (id, settled_for, currency, ran_at,
                    statement_rows, statement_total, matched, missing_from_statement,
                    extra_on_statement, amounts_differ, unmatchable)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                found.runId(),
                found.day(),
                found.currency(),
                Timestamp.from(now),
                found.statementRows(),
                found.statementTotal(),
                found.matched(),
                found.missingFromStatement(),
                found.extraOnStatement(),
                found.amountsDiffer(),
                found.unmatchable());
    }

    /**
     * Writes down a difference, or notes that it is still there.
     *
     * <p>The second case is the important one. A difference found again by a later run is the
     * same difference, not a new one, so the row keeps the moment it was first seen and gains
     * the moment it was last seen. Anything else would mean a re-run after an incident
     * producing a page of problems that all look brand new.
     */
    private void remember(
            Difference difference, LocalDate day, String currency, UUID runId, Instant now) {

        jdbc.update(
                """
                insert into reconciliation_difference (id, settled_for, currency,
                    acquirer_reference, outcome, platform_amount, statement_amount,
                    merchant_id, payment_id, first_seen_at, last_seen_at, first_seen_in)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (settled_for, currency, acquirer_reference, outcome)
                    do update set last_seen_at = excluded.last_seen_at
                """,
                UUID.randomUUID(),
                day,
                currency,
                difference.reference(),
                difference.outcome(),
                difference.platformAmount(),
                difference.statementAmount(),
                difference.merchantId(),
                difference.paymentId(),
                Timestamp.from(now),
                Timestamp.from(now),
                runId);
    }

    /** Every run for a day, most recent first, which is how a re-run is compared to the last. */
    public List<Map<String, Object>> runsFor(LocalDate day) {
        return jdbc.queryForList(
                """
                select id, settled_for, currency, ran_at, statement_rows, statement_total,
                       matched, missing_from_statement, extra_on_statement, amounts_differ,
                       unmatchable
                from reconciliation_run
                where settled_for = ?
                order by ran_at desc
                """,
                day);
    }

    /** Everything still outstanding, oldest first: the queue a person works. */
    public List<Map<String, Object>> differences(int limit) {
        return jdbc.queryForList(
                """
                select id, settled_for, currency, acquirer_reference, outcome,
                       platform_amount, statement_amount, merchant_id, payment_id,
                       first_seen_at, last_seen_at
                from reconciliation_difference
                order by settled_for, acquirer_reference
                limit ?
                """,
                limit);
    }

    /** One difference, and everything a person needs to act on it. */
    private record Difference(
            String outcome,
            String reference,
            Long platformAmount,
            Long statementAmount,
            UUID merchantId,
            UUID paymentId) {

        boolean is(String kind) {
            return outcome.equals(kind);
        }

        static Difference extra(AcquirerStatements.Settled settled) {
            // Nothing on this side to name. A transaction only the bank has may be another
            // platform's, a duplicate of theirs, or a genuine payment this platform lost.
            return new Difference(
                    "EXTRA_ON_STATEMENT", settled.reference(), null, settled.amount(), null, null);
        }

        static Difference missing(String reference, Map<String, Object> mine) {
            return new Difference(
                    "MISSING_FROM_STATEMENT",
                    reference,
                    ((Number) mine.get("amount")).longValue(),
                    null,
                    (UUID) mine.get("merchant_id"),
                    (UUID) mine.get("payment_id"));
        }

        static Difference differing(
                AcquirerStatements.Settled settled, Map<String, Object> mine, long ourAmount) {

            return new Difference(
                    "AMOUNTS_DIFFER",
                    settled.reference(),
                    ourAmount,
                    settled.amount(),
                    (UUID) mine.get("merchant_id"),
                    (UUID) mine.get("payment_id"));
        }

        static Difference unmatchable(Map<String, Object> mine) {
            // Keyed by the payment, because there is no acquirer reference to key it by and
            // that is precisely the problem.
            return new Difference(
                    "UNMATCHABLE",
                    "payment:" + mine.get("payment_id"),
                    ((Number) mine.get("amount")).longValue(),
                    null,
                    (UUID) mine.get("merchant_id"),
                    (UUID) mine.get("payment_id"));
        }
    }
}
