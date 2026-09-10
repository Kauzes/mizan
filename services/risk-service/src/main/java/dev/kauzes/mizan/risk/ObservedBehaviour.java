package dev.kauzes.mizan.risk;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What has actually happened, and what it says about what is normal.
 *
 * <p>Everything here is a projection of the payment events this service consumes. It reads no
 * other service's tables, and replaying the topic rebuilds these rows rather than doubling
 * them — which is the property that makes it a projection rather than a cache with better
 * manners.
 */
@Component
public class ObservedBehaviour {

    private static final Logger log = LoggerFactory.getLogger(ObservedBehaviour.class);

    /**
     * How recently a payment counts as "just now" for card testing.
     *
     * <p>Five minutes, because somebody working through a list of stolen numbers does it in a
     * burst and a person shopping does not. Long enough to catch the burst, short enough that
     * a customer who genuinely bought three things does not look like one.
     */
    private static final Duration RECENTLY = Duration.ofMinutes(5);

    /**
     * And how recently a decline still counts against a card.
     *
     * <p>Longer, because a card that was refused an hour ago and is being tried again is a more
     * interesting fact than one refused last week.
     */
    private static final Duration DECLINED_RECENTLY = Duration.ofHours(1);

    /**
     * How many payments before a country counts as familiar.
     *
     * <p>Two, not one. A single payment from somewhere is not a pattern, and treating it as one
     * means the first fraudulent payment from a new country teaches the platform to expect
     * more of them — which is the baseline being poisoned by exactly the thing it is meant to
     * catch.
     */
    private static final int FAMILIAR_AFTER = 2;

    /**
     * How few payments make a baseline a rumour rather than a baseline.
     *
     * <p>Below this the amount rule stays silent. A median of three payments is not a measure
     * of anything, and a scorer confidently comparing against one is worse than a scorer with
     * no opinion.
     */
    private static final int ENOUGH_TO_MEAN_SOMETHING = 10;

    private final JdbcTemplate jdbc;

    public ObservedBehaviour(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that a payment happened, and updates what that makes normal.
     *
     * <p>Called from inside the inbox transaction, so the record of having handled the event
     * and the effect of handling it commit together.
     */
    public void record(
            UUID eventId,
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String cardFingerprint,
            String cardCountry,
            String outcome,
            Instant occurredAt) {

        int written = jdbc.update(
                "insert into observed_payment (event_id, payment_id, merchant_id, amount, "
                        + "currency, card_fingerprint, card_country, outcome, occurred_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        // Replaying the topic rebuilds rather than doubles.
                        + "on conflict (payment_id, outcome) do nothing",
                eventId,
                paymentId,
                merchantId,
                amount,
                currency,
                cardFingerprint,
                cardCountry,
                outcome,
                Timestamp.from(occurredAt));

        if (written == 0) {
            return;
        }

        // Only an approved payment tells us what this merchant normally takes. A declined one
        // says something about the card and nothing about the merchant's ordinary business,
        // and letting declines into the baseline is how a burst of fraud attempts moves what
        // the platform thinks is normal.
        if ("APPROVED".equals(outcome)) {
            recomputeBaseline(merchantId);
            if (cardCountry != null) {
                seenCountry(merchantId, cardCountry, occurredAt);
            }
        }
    }

    /**
     * Recomputes this merchant's typical amount from what has actually been approved.
     *
     * <p>A median rather than a mean. One car sold by a coffee shop should not redefine what a
     * coffee costs, and a mean lets a single outlier do exactly that — which matters here more
     * than usual, because the outlier a mean would chase is often the fraud.
     */
    private void recomputeBaseline(UUID merchantId) {
        jdbc.update(
                "insert into merchant_baseline (merchant_id, typical_amount, payments_seen, "
                        + "updated_at) "
                        + "select ?, "
                        + "  coalesce(percentile_cont(0.5) within group (order by amount), 0)::bigint, "
                        + "  count(*), now() "
                        + "from observed_payment where merchant_id = ? and outcome = 'APPROVED' "
                        + "on conflict (merchant_id) do update set "
                        + "  typical_amount = excluded.typical_amount, "
                        + "  payments_seen = excluded.payments_seen, "
                        + "  updated_at = excluded.updated_at",
                merchantId,
                merchantId);
    }

    private void seenCountry(UUID merchantId, String country, Instant at) {
        jdbc.update(
                "insert into merchant_country (merchant_id, country, seen, first_seen_at) "
                        + "values (?, ?, 1, ?) "
                        + "on conflict (merchant_id, country) do update set seen = merchant_country.seen + 1",
                merchantId,
                country,
                Timestamp.from(at));
    }

    /**
     * Everything the scorer is allowed to compare this payment against.
     *
     * <p>Assembled here and handed over, so that {@link Scorer} stays a pure function and every
     * lookup in this method can change without a rule changing.
     */
    public WhatWeKnow about(
            UUID merchantId, String cardFingerprint, Instant at, int reviewAbove, int blockAbove) {

        long typical = typicalAmountOf(merchantId);
        return new WhatWeKnow(
                typical,
                recentAttempts(merchantId, cardFingerprint, at),
                wasDeclinedRecently(merchantId, cardFingerprint, at),
                hasPaidBefore(merchantId, cardFingerprint),
                familiarCountriesOf(merchantId),
                reviewAbove,
                blockAbove);
    }

    /**
     * The merchant's typical amount, or zero while there is not enough to say.
     *
     * <p>Zero means "no opinion", and the scorer treats it as such. A baseline from three
     * payments is a rumour, and a scorer confidently comparing against one is worse than a
     * scorer with none.
     */
    private long typicalAmountOf(UUID merchantId) {
        return jdbc
                .query(
                        "select typical_amount, payments_seen from merchant_baseline "
                                + "where merchant_id = ?",
                        (row, index) -> row.getInt("payments_seen") >= ENOUGH_TO_MEAN_SOMETHING
                                ? row.getLong("typical_amount")
                                : 0L,
                        merchantId)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    private int recentAttempts(UUID merchantId, String cardFingerprint, Instant at) {
        if (cardFingerprint == null) {
            return 0;
        }
        Integer counted = jdbc.queryForObject(
                "select count(*) from observed_payment where merchant_id = ? "
                        + "and card_fingerprint = ? and occurred_at > ?",
                Integer.class,
                merchantId,
                cardFingerprint,
                Timestamp.from(at.minus(RECENTLY)));
        return counted == null ? 0 : counted;
    }

    private boolean wasDeclinedRecently(UUID merchantId, String cardFingerprint, Instant at) {
        if (cardFingerprint == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from observed_payment where merchant_id = ? "
                        + "and card_fingerprint = ? and outcome = 'DECLINED' and occurred_at > ?)",
                Boolean.class,
                merchantId,
                cardFingerprint,
                Timestamp.from(at.minus(DECLINED_RECENTLY))));
    }

    private boolean hasPaidBefore(UUID merchantId, String cardFingerprint) {
        if (cardFingerprint == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from observed_payment where merchant_id = ? "
                        + "and card_fingerprint = ? and outcome = 'APPROVED')",
                Boolean.class,
                merchantId,
                cardFingerprint));
    }

    /** The countries this merchant has been paid from more than once. */
    private List<String> familiarCountriesOf(UUID merchantId) {
        return jdbc.queryForList(
                "select country from merchant_country where merchant_id = ? and seen >= ?",
                String.class,
                merchantId,
                FAMILIAR_AFTER);
    }

    /**
     * Rebuilds every baseline from the observed payments.
     *
     * <p>Here because a projection that cannot be rebuilt is a cache, and because the test that
     * proves it is the one that proves this is a projection at all.
     */
    public void rebuildEverything() {
        List<UUID> merchants = jdbc.queryForList(
                "select distinct merchant_id from observed_payment", UUID.class);

        jdbc.update("delete from merchant_baseline");
        merchants.forEach(this::recomputeBaseline);
        log.info("rebuilt {} merchant baseline(s) from observed payments", merchants.size());
    }
}
