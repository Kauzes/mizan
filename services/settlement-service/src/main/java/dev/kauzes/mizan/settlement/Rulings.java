package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a person decided about a difference, and the queue of differences nobody has decided
 * about yet.
 *
 * <p>A reconciliation nobody reads is a reconciliation that was not run. MIZ-72 made the
 * comparison; this is the part that puts it in front of somebody, keeps it there, and records
 * what they did about it.
 *
 * <p>Two rulings and no more. A difference is <b>acknowledged</b> — a person has looked,
 * understands it, and nothing about the money changes — or it is <b>corrected</b>, which means
 * an entry has been posted in the ledger and the ruling names it. Nothing here posts that
 * entry. An endpoint that moved money on an operator's say-so would be a way to write the
 * books by hand, which is exactly what a double entry ledger exists to make impossible, and it
 * is the same line MIZ-53 drew for stuck payments.
 *
 * <p>A correction is checked rather than believed. The ledger is asked whether the entry
 * exists, and a ruling naming an entry that does not is refused — a decision recorded as
 * evidence has to be evidence. An unreachable ledger is not treated as a missing entry: the
 * ruling does not happen, because recording a claim that could not be checked would put it
 * beyond checking forever.
 *
 * <p>Nothing falls off this queue by itself. Whether a difference is outstanding is derived
 * from whether anybody has ruled on it, never from whether the latest run still sees it — a
 * difference that stopped being reported because a later run did not notice it is a difference
 * nobody decided about. A difference the newest run did not see is still listed, and says so,
 * because "it went away" is a thing a person needs to be told rather than a reason to stop
 * telling them.
 */
@Service
public class Rulings {

    private static final Logger log = LoggerFactory.getLogger(Rulings.class);

    /** A person has looked and explained it. Nothing about the money changes. */
    private static final String ACKNOWLEDGED = "ACKNOWLEDGED";

    /** An entry was posted in the ledger, and this ruling names it. */
    private static final String CORRECTED = "CORRECTED";

    private final JdbcTemplate jdbc;
    private final LedgerBooks books;

    public Rulings(JdbcTemplate jdbc, LedgerBooks books) {
        this.jdbc = jdbc;
        this.books = books;
    }

    /**
     * Every difference nobody has ruled on, oldest first, which is the queue a person works.
     *
     * <p>Each row carries what it is about, both figures where both exist, how long it has been
     * outstanding, and whether the most recent run for its day still found it. That last one is
     * the awkward column, and it is here on purpose.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> outstanding(int limit) {
        return jdbc.queryForList(
                """
                select d.id, d.settled_for, d.currency, d.acquirer_reference, d.outcome,
                       d.platform_amount, d.statement_amount, d.merchant_id, d.payment_id,
                       d.first_seen_at, d.last_seen_at,
                       d.last_seen_at < (
                           select max(r.ran_at) from reconciliation_run r
                           where r.settled_for = d.settled_for and r.currency = d.currency
                       ) as no_longer_reported
                from reconciliation_difference d
                where not exists (
                    select 1 from reconciliation_ruling g where g.difference_id = d.id
                )
                order by d.first_seen_at, d.acquirer_reference
                limit ?
                """,
                limit);
    }

    /**
     * How much is waiting, and how long the oldest has been waiting.
     *
     * <p>Counted rather than derived from the list, because the list is capped and a queue that
     * says "500" when it holds nine thousand is worse than no number at all.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> howMuchIsWaiting() {
        Map<String, Object> answer = new LinkedHashMap<>();
        Map<String, Object> counted = jdbc.queryForMap(
                """
                select count(*) as outstanding, min(first_seen_at) as oldest
                from reconciliation_difference d
                where not exists (
                    select 1 from reconciliation_ruling g where g.difference_id = d.id
                )
                """);

        long outstanding = ((Number) counted.get("outstanding")).longValue();
        Timestamp oldest = (Timestamp) counted.get("oldest");

        answer.put("outstanding", outstanding);
        answer.put("oldest", oldest == null ? null : oldest.toInstant().toString());
        answer.put("byOutcome", byOutcome());
        return answer;
    }

    private Map<String, Object> byOutcome() {
        Map<String, Object> tally = new LinkedHashMap<>();
        jdbc.queryForList(
                        """
                        select d.outcome as outcome, count(*) as how_many
                        from reconciliation_difference d
                        where not exists (
                            select 1 from reconciliation_ruling g where g.difference_id = d.id
                        )
                        group by d.outcome
                        order by d.outcome
                        """)
                .forEach(row -> tally.put(
                        (String) row.get("outcome"), ((Number) row.get("how_many")).longValue()));
        return tally;
    }

    /** What has been decided lately, for anybody asking what has been going on. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList(
                """
                select g.id, g.difference_id, g.ruling, g.ruled_by, g.why, g.corrected_by,
                       g.ruled_at, d.settled_for, d.currency, d.acquirer_reference, d.outcome
                from reconciliation_ruling g
                join reconciliation_difference d on d.id = g.difference_id
                order by g.ruled_at desc
                limit ?
                """,
                limit);
    }

    /** Every ruling about one difference, oldest first, because the order is the story. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> about(UUID differenceId) {
        return jdbc.queryForList(
                """
                select ruling, ruled_by, why, corrected_by, ruled_at
                from reconciliation_ruling
                where difference_id = ?
                order by ruled_at
                """,
                differenceId);
    }

    /**
     * Records what a person decided.
     *
     * <p>The ruling is written after the ledger has been asked about any entry it names, so a
     * correction nobody can find is refused rather than recorded. Two rulings about the same
     * difference are both kept: a person who acknowledged something on Monday and corrected it
     * on Thursday did two things, and a record that kept only the second would be a record of
     * a decision that was never taken.
     */
    @Transactional
    public Map<String, Object> rule(
            UUID differenceId, String ruling, String ruledBy, String why, UUID correctedBy) {

        Map<String, Object> difference = find(differenceId);
        String decision = ruling == null ? "" : ruling.strip().toUpperCase(Locale.ROOT);

        if (ruledBy == null || ruledBy.isBlank() || why == null || why.isBlank()) {
            throw new UnprocessableException(
                    "ruledBy and why are both required: a decision nobody owns and nobody "
                            + "explained is not an audit trail.");
        }

        switch (decision) {
            case ACKNOWLEDGED -> {
                if (correctedBy != null) {
                    throw new UnprocessableException(
                            "An acknowledgement says nothing about the books. If an entry was "
                                    + "posted, this is a correction and names it.");
                }
            }
            case CORRECTED -> checkTheCorrectionExists(correctedBy, difference);
            default -> throw new UnprocessableException(
                    "A difference is either ACKNOWLEDGED, which changes nothing about the "
                            + "money, or CORRECTED, which names the entry that did.");
        }

        Instant now = Instant.now();
        jdbc.update(
                """
                insert into reconciliation_ruling (id, difference_id, ruling, ruled_by, why,
                    corrected_by, ruled_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                differenceId,
                decision,
                ruledBy.strip(),
                why.strip(),
                correctedBy,
                Timestamp.from(now));

        // At warning level, because somebody deciding by hand that this platform and a bank
        // disagree for a reason is exactly the kind of thing that should be in a log an
        // operator reads rather than one they grep after an incident.
        log.warn(
                "{} on {} ({}) was {} by {}: {}{}",
                difference.get("outcome"),
                difference.get("settled_for"),
                difference.get("acquirer_reference"),
                decision,
                ruledBy,
                why,
                correctedBy == null ? "" : " (corrected by entry " + correctedBy + ")");

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("difference", differenceId);
        answer.put("ruling", decision);
        answer.put("ruledBy", ruledBy.strip());
        answer.put("ruledAt", now.toString());
        answer.put("correctedBy", correctedBy);
        answer.put(
                "changed",
                CORRECTED.equals(decision)
                        ? "nothing here; the entry named above is what moved the money"
                        : "nothing; this difference is explained rather than undone");
        return answer;
    }

    /**
     * Asks the books whether the correction is really there.
     *
     * <p>And whether it is the right merchant's, when the difference knows whose it is. An
     * entry that exists but belongs to somebody else is not a correction of this difference,
     * and accepting it would let a real difference be closed by pointing at an unrelated
     * movement.
     */
    private void checkTheCorrectionExists(UUID correctedBy, Map<String, Object> difference) {
        if (correctedBy == null) {
            throw new UnprocessableException(
                    "A correction names the entry that made it. Ruling on a difference never "
                            + "moves money: the entry is posted in the ledger, where it is an "
                            + "entry like any other and just as visible.");
        }

        Optional<LedgerBooks.PostedEntry> posted = books.entry(correctedBy);
        if (posted.isEmpty()) {
            throw new UnprocessableException(
                    "The books have no entry " + correctedBy + ". A correction that cannot be "
                            + "found is not a correction.");
        }

        UUID whose = (UUID) difference.get("merchant_id");
        if (whose != null && !whose.equals(posted.get().merchantId())) {
            throw new UnprocessableException(
                    "Entry " + correctedBy + " is not in this merchant's books, so it does not "
                            + "correct this difference.");
        }
    }

    private Map<String, Object> find(UUID differenceId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                """
                select id, settled_for, currency, acquirer_reference, outcome, merchant_id
                from reconciliation_difference where id = ?
                """,
                differenceId);

        if (found.isEmpty()) {
            throw new NotFoundException("No reconciliation difference with that id.");
        }
        return found.getFirst();
    }

    /**
     * Turns an unparseable id into the platform's own shape of refusal.
     *
     * <p>Naming what it was supposed to be the id of, because an operator who has pasted the
     * entry id into the difference field needs to be told which of the two is wrong.
     */
    static UUID idOf(String id, String what) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw new MizanException(
                    ErrorCode.UNPROCESSABLE, id + " is not the id of " + what + ".", notAnId);
        }
    }
}
