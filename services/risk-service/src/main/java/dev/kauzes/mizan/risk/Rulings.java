package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.common.error.ConflictException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What analysts decided, and what the scorer learns from it.
 *
 * <p>The only place on this platform where what somebody did changes what the platform will
 * decide next time. That makes it the mechanism most worth being careful about, and the care is
 * all in the bounds: a loop nobody bounded is a loop an attacker teaches.
 */
@Component
public class Rulings {

    private static final Logger log = LoggerFactory.getLogger(Rulings.class);

    /**
     * How far the loop may move a merchant's line, in either direction.
     *
     * <p>Twenty points against thresholds that start at forty and seventy — enough to matter
     * and not enough to turn a scorer off. Somebody who could get an unlimited number of
     * payments released could otherwise teach the platform to stop looking, one ruling at a
     * time, and the last thing they would need is a way to make that go faster.
     *
     * <p>Also enforced by a check constraint, because a bound the loop itself enforces is a
     * bound the loop can be wrong about.
     */
    private static final int FURTHEST = 20;

    /**
     * How many consecutive rulings the same way before the line moves at all.
     *
     * <p>Three, so that one analyst having a generous afternoon does not move anything. The
     * signal being learned from is a *pattern* of agreement, and a pattern is not one event.
     */
    private final int consistentRulings;

    /** And how far it moves when it does. Small, so the loop is slow and observable. */
    private static final int STEP = 5;

    private final JdbcTemplate jdbc;

    public Rulings(
            JdbcTemplate jdbc,
            @Value("${mizan.risk.rulings-before-learning:3}") int consistentRulings) {

        this.jdbc = jdbc;
        this.consistentRulings = consistentRulings;
    }

    /**
     * Records a ruling and lets the scorer learn from it, if there is a pattern to learn.
     *
     * <p>Both in one transaction: a ruling the platform recorded but did not learn from, or
     * learned from without recording, are each half of a decision.
     */
    @Transactional
    public Map<String, Object> record(
            UUID merchantId,
            UUID paymentId,
            String ruling,
            Integer riskScore,
            String riskReasons,
            String ruledBy,
            String why) {

        try {
            jdbc.update(
                    "insert into ruling (id, merchant_id, payment_id, ruling, risk_score, "
                            + "risk_reasons, ruled_by, why, at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    merchantId,
                    paymentId,
                    ruling,
                    riskScore,
                    riskReasons,
                    ruledBy,
                    why,
                    Timestamp.from(Instant.now()));

        } catch (DataIntegrityViolationException already) {
            // Somebody already ruled on this payment. Told as a conflict rather than as a
            // failure, because it is not one: two analysts reached the same queue entry, and
            // the second one needs to know that the first one's ruling stands.
            throw new ConflictException("This payment has already been ruled on.");
        }

        int moved = learnFrom(merchantId, ruling);
        log.info(
                "{} ruled {} on payment {}: {}{}",
                ruledBy,
                ruling,
                paymentId,
                why,
                moved == 0 ? "" : " (the line moved by " + moved + ")");

        return Map.of(
                "paymentId", paymentId,
                "ruling", ruling,
                "ruledBy", ruledBy,
                "thresholdMovedBy", moved,
                "learnedAdjustment", adjustmentFor(merchantId));
    }

    /**
     * Moves the merchant's line, once every few rulings that all went the same way.
     *
     * <p>Consecutive, not cumulative. A merchant whose analysts release three in a row is
     * telling the platform it is too suspicious; a merchant whose analysts disagree with each
     * other is telling it nothing, and a count that never reset would eventually move on noise.
     *
     * <p>Every few rather than every one after the first few. A run of six is a stronger signal
     * than a run of three and should be worth more, but it should be worth twice as much rather
     * than four times: moving on each ruling past the third reaches the limit in six, which is
     * not the slow loop this is supposed to be.
     *
     * @return how far the line moved, which is usually zero
     */
    private int learnFrom(UUID merchantId, String ruling) {
        if (runOfTheSameRuling(merchantId, ruling) % consistentRulings != 0) {
            return 0;
        }

        // Released means the platform was too suspicious, so the line goes up and fewer
        // payments are held. Refused means it was not suspicious enough, so it comes down.
        int direction = "RELEASED".equals(ruling) ? STEP : -STEP;
        int was = adjustmentFor(merchantId);
        int now = Math.max(-FURTHEST, Math.min(FURTHEST, was + direction));

        if (now == was) {
            // Already as far as the loop is allowed to go. Worth saying out loud: a merchant
            // whose analysts keep disagreeing with a scorer that cannot move any further is a
            // merchant whose thresholds a person should look at.
            log.warn(
                    "merchant {} has ruled {} consistently and the learned adjustment is "
                            + "already at its limit of {}; somebody should look at their "
                            + "thresholds",
                    merchantId,
                    ruling,
                    was);
            return 0;
        }

        jdbc.update(
                "insert into merchant_thresholds (merchant_id, review_above, block_above, "
                        + "set_by, updated_at, learned_adjustment) "
                        + "values (?, 40, 70, 'learned from rulings', now(), ?) "
                        + "on conflict (merchant_id) do update set "
                        + "learned_adjustment = excluded.learned_adjustment, updated_at = now()",
                merchantId,
                now);

        return now - was;
    }

    /**
     * How many rulings in a row have gone this way, counting back from now.
     *
     * <p>Asked of the database rather than by reading a page of rulings, so that a run of any
     * length is counted exactly. A run is everything since the last ruling that went the other
     * way, which is the definition, and the index on (merchant, at) is what makes it cheap.
     */
    private int runOfTheSameRuling(UUID merchantId, String ruling) {
        Integer counted = jdbc.queryForObject(
                "select count(*) from ruling where merchant_id = ? and at > coalesce("
                        + "(select max(at) from ruling where merchant_id = ? and ruling <> ?), "
                        + "'-infinity'::timestamptz)",
                Integer.class,
                merchantId,
                merchantId,
                ruling);

        return counted == null ? 0 : counted;
    }

    /** How far the loop has moved this merchant's line from where a person set it. */
    public int adjustmentFor(UUID merchantId) {
        return jdbc
                .queryForList(
                        "select learned_adjustment from merchant_thresholds where merchant_id = ?",
                        Integer.class,
                        merchantId)
                .stream()
                .findFirst()
                .orElse(0);
    }

    /** What has been ruled for this merchant, most recent first. */
    public List<Map<String, Object>> forMerchant(UUID merchantId, int limit) {
        return jdbc.queryForList(
                "select payment_id, ruling, risk_score, risk_reasons, ruled_by, why, at "
                        + "from ruling where merchant_id = ? order by at desc limit ?",
                merchantId,
                limit);
    }
}
