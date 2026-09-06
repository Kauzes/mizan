package dev.kauzes.mizan.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a person decided about something the platform could not.
 *
 * <p>Append only, and a table of its own rather than a column on the thing it is about. A
 * decision is evidence of what somebody did, and evidence the next decision can overwrite is
 * not evidence — the same reasoning that made the journal append only.
 */
@Component
public class OperatorDecisions {

    private final JdbcTemplate jdbc;

    public OperatorDecisions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a decision and what it changed.
     *
     * <p>What it changed rather than what was intended, so the record is of an effect. "Retried
     * it" and "asked for it to be retried" are different claims, and only one of them is
     * checkable afterwards.
     */
    public void record(
            UUID merchantId,
            String kind,
            UUID subjectId,
            String decision,
            String who,
            String why,
            String changed) {

        jdbc.update(
                "insert into operator_decision (id, subject_kind, subject_id, merchant_id, "
                        + "decision, decided_by, why, changed, at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                kind,
                subjectId,
                merchantId,
                decision,
                who,
                why,
                changed,
                Timestamp.from(Instant.now()));
    }

    /** Every decision about one thing, oldest first, because the order is the story. */
    public List<Map<String, Object>> about(String kind, UUID subjectId) {
        return jdbc.queryForList(
                "select decision, decided_by, why, changed, at from operator_decision "
                        + "where subject_kind = ? and subject_id = ? order by at",
                kind,
                subjectId);
    }

    /** Everything decided recently, for anybody asking what has been going on. */
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList(
                "select subject_kind, subject_id, decision, decided_by, why, changed, at "
                        + "from operator_decision order by at desc limit ?",
                limit);
    }
}
