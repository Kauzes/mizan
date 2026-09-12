package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.UnprocessableException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * How a merchant's business is, in figures the database works out.
 *
 * <p>The first read model here: a query shaped for a question rather than for a record. It is
 * computed on demand rather than maintained as events arrive, which is the decision worth
 * saying out loud.
 *
 * <p>A maintained projection would be faster and would be a second copy of the truth — one
 * that needs backfilling when a rule changes, reconciling when a message is lost, and being
 * wrong in a way nobody notices until a merchant asks why two screens disagree. The payments
 * table is already indexed by merchant and time, and a few aggregates over a range are what a
 * database is for. At the scale this platform is at, the simpler answer is also the honest
 * one, and the point at which it stops being so is visible: these queries get slow together.
 *
 * <p>Everything is counted by the database and nothing by a caller. A dashboard that fetched a
 * thousand payments to count them is a dashboard that stops working exactly when a merchant
 * becomes worth having.
 */
@Component
public class HowBusinessIs {

    /** The longest range this answers in one query. A year of days is a readable chart. */
    private static final int MOST_DAYS = 366;

    private final JdbcTemplate jdbc;

    public HowBusinessIs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What happened in a range, and what still needs somebody.
     *
     * @param zone which days these are. A merchant in Istanbul asking about Tuesday means
     *     their Tuesday, and bucketing in UTC would put three hours of it on Monday
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forMerchant(UUID merchantId, Instant from, Instant to, String zone) {
        if (!from.isBefore(to)) {
            throw new UnprocessableException("That range starts after it ends.");
        }
        if (java.time.Duration.between(from, to).toDays() > MOST_DAYS) {
            throw new UnprocessableException(
                    "This answers about " + MOST_DAYS + " days at a time.");
        }

        String day = dayIn(zone);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("from", from.toString());
        answer.put("to", to.toString());
        answer.put("zone", day);

        Map<String, Object> totals = totals(merchantId, from, to);
        answer.put("totals", totals);
        answer.put("authorizationRate", rate(totals));
        answer.put("volume", volume(merchantId, from, to));
        answer.put("byDay", byDay(merchantId, from, to, day));
        answer.put("refusals", refusals(merchantId, from, to));
        answer.put("needsSomebody", needsSomebody(merchantId));

        return answer;
    }

    /**
     * The buckets, defined from the payment row rather than from its history.
     *
     * <p>Deliberately explicit about what "authorized" means: a payment the acquirer approved,
     * whether it was later captured or voided. And about what "attempted" means: a payment
     * that left CREATED. An intent nobody ever authorized is not a refusal, and counting it as
     * one would make a merchant who creates intents speculatively look like a merchant with a
     * problem.
     */
    private Map<String, Object> totals(UUID merchantId, Instant from, Instant to) {
        Map<String, Object> counted = jdbc.queryForMap(
                """
                select
                  count(*) filter (where status <> 'CREATED') as attempted,
                  count(*) filter (where status in ('AUTHORIZED', 'CAPTURED', 'VOIDED'))
                      as authorized,
                  count(*) filter (where status = 'CAPTURED') as captured,
                  count(*) filter (where status = 'DECLINED' and acquirer_reference is not null)
                      as declined_by_acquirer,
                  count(*) filter (where status = 'DECLINED' and acquirer_reference is null)
                      as refused_by_platform,
                  count(*) filter (where status = 'HELD_FOR_REVIEW') as held,
                  count(*) filter (where status = 'AUTHORIZATION_UNKNOWN') as unknown,
                  count(*) as created
                from payment
                where merchant_id = ? and created_at >= ? and created_at < ?
                """,
                merchantId,
                Timestamp.from(from),
                Timestamp.from(to));

        // A LinkedHashMap of longs rather than whatever the driver handed back, so the answer
        // is the same shape whichever database is underneath.
        Map<String, Object> totals = new LinkedHashMap<>();
        counted.forEach((name, value) -> totals.put(camel(name), ((Number) value).longValue()));
        return totals;
    }

    /**
     * What went through, as a fraction of what was tried.
     *
     * <p>Null rather than zero when nothing was tried. A rate of zero says every payment
     * failed; no payments at all says something else entirely, and a dashboard that showed
     * 0% to a merchant who took the day off would be lying to them.
     */
    private static Double rate(Map<String, Object> totals) {
        long attempted = (long) totals.get("attempted");
        return attempted == 0 ? null : (long) totals.get("authorized") / (double) attempted;
    }

    /**
     * Money, per currency, never summed across them.
     *
     * <p>Adding lira to yen produces a number with no meaning, and a dashboard that did it
     * would be believed.
     */
    private List<Map<String, Object>> volume(UUID merchantId, Instant from, Instant to) {
        return jdbc.queryForList(
                """
                select currency,
                       sum(amount) filter (where status = 'CAPTURED') as captured,
                       sum(refunded_amount) as refunded,
                       count(*) filter (where status = 'CAPTURED') as payments
                from payment
                where merchant_id = ? and created_at >= ? and created_at < ?
                group by currency
                having sum(amount) filter (where status = 'CAPTURED') is not null
                order by captured desc
                """,
                merchantId,
                Timestamp.from(from),
                Timestamp.from(to));
    }

    /**
     * A row per day, with the volume behind each rate.
     *
     * <p>A rate without a denominator is a rumour: ninety percent of ten payments and ninety
     * percent of ten thousand are different facts, and a chart that showed only the line would
     * make a quiet Sunday look like an incident.
     *
     * <p>Only days that had something. A merchant reading a chart can see a gap; a row of
     * zeroes reads as an outage.
     */
    private List<Map<String, Object>> byDay(
            UUID merchantId, Instant from, Instant to, String zone) {

        List<Map<String, Object>> days = jdbc.queryForList(
                """
                select (created_at at time zone ?)::date as day,
                  count(*) filter (where status <> 'CREATED') as attempted,
                  count(*) filter (where status in ('AUTHORIZED', 'CAPTURED', 'VOIDED'))
                      as authorized,
                  count(*) filter (where status = 'CAPTURED') as captured,
                  count(*) filter (where status = 'DECLINED') as declined,
                  count(*) filter (where status = 'HELD_FOR_REVIEW') as held
                from payment
                where merchant_id = ? and created_at >= ? and created_at < ?
                group by 1
                order by 1
                """,
                zone,
                merchantId,
                Timestamp.from(from),
                Timestamp.from(to));

        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> day : days) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", String.valueOf(day.get("day")));
            for (String bucket : List.of("attempted", "authorized", "captured", "declined", "held")) {
                row.put(bucket, ((Number) day.get(bucket)).longValue());
            }
            row.put("volume", new ArrayList<Map<String, Object>>());
            byDate.put(row.get("day").toString(), row);
        }

        // The money behind those counts, per currency, joined in here rather than in one
        // query with a cross product of days and currencies.
        List<Map<String, Object>> money = jdbc.queryForList(
                """
                select (created_at at time zone ?)::date as day, currency, sum(amount) as captured
                from payment
                where merchant_id = ? and created_at >= ? and created_at < ?
                  and status = 'CAPTURED'
                group by 1, 2
                order by 1, 2
                """,
                zone,
                merchantId,
                Timestamp.from(from),
                Timestamp.from(to));

        for (Map<String, Object> row : money) {
            Map<String, Object> day = byDate.get(String.valueOf(row.get("day")));
            if (day == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> volume = (List<Map<String, Object>>) day.get("volume");
            volume.add(Map.of(
                    "currency", row.get("currency"),
                    "captured", ((Number) row.get("captured")).longValue()));
        }

        return List.copyOf(byDate.values());
    }

    /**
     * Why payments did not go through, and who refused them.
     *
     * <p>Split on purpose. "The acquirer said insufficient funds" and "this platform held it
     * for review" are different problems with different fixes, and a single failed count hides
     * both. The acquirer's own words are kept rather than translated: a merchant asking their
     * customer's bank about a decline needs the reason that bank gave.
     */
    private List<Map<String, Object>> refusals(UUID merchantId, Instant from, Instant to) {
        return jdbc.queryForList(
                """
                select
                  case when acquirer_reference is null then 'PLATFORM' else 'ACQUIRER' end as by,
                  coalesce(decline_reason, 'no reason was recorded') as reason,
                  count(*) as payments
                from payment
                where merchant_id = ? and created_at >= ? and created_at < ?
                  and status = 'DECLINED'
                group by 1, 2
                order by payments desc, reason
                """,
                merchantId,
                Timestamp.from(from),
                Timestamp.from(to));
    }

    /**
     * What is outstanding right now, whatever the range asked about.
     *
     * <p>Not scoped to the range on purpose: a payment held three weeks ago is still held, and
     * a merchant looking at last week should not have to widen a date filter to discover that
     * somebody is waiting.
     */
    private Map<String, Object> needsSomebody(UUID merchantId) {
        Map<String, Object> counted = jdbc.queryForMap(
                """
                select
                  -- Snake case aliases, because an unquoted one comes back lowercased and
                  -- "waitingforaperson" is not a field name anybody should read.
                  count(*) filter (
                    where status = 'HELD_FOR_REVIEW'
                      and review_ruling is null) as waiting_for_a_person,
                  count(*) filter (
                    where needs_attention_since is not null
                      and attention_handled_at is null) as needing_an_operator
                from payment
                where merchant_id = ?
                """,
                merchantId);

        Map<String, Object> answer = new LinkedHashMap<>();
        counted.forEach((name, value) -> answer.put(camel(name), ((Number) value).longValue()));
        return answer;
    }

    /**
     * The zone these days are counted in, checked against the ones that exist.
     *
     * <p>This string is concatenated into nothing — it is a bound parameter — but it is still
     * a caller's text reaching the database, and an unknown zone is better refused than
     * silently answered about a different day.
     */
    private static String dayIn(String zone) {
        if (zone == null || zone.isBlank()) {
            return "UTC";
        }
        if (!ZoneId.getAvailableZoneIds().contains(zone) && !"UTC".equals(zone)) {
            throw new UnprocessableException("There is no time zone called " + zone + ".");
        }
        return zone;
    }

    /** Postgres answers in snake case; the rest of this API does not. */
    private static String camel(String column) {
        StringBuilder camel = new StringBuilder();
        boolean up = false;
        for (char letter : column.toCharArray()) {
            if (letter == '_') {
                up = true;
            } else {
                camel.append(up ? Character.toUpperCase(letter) : letter);
                up = false;
            }
        }
        return camel.toString();
    }
}
