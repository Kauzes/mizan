package dev.kauzes.mizan.common.web;

import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Where a key and what it produced are kept.
 *
 * <p>Plain SQL against one table that every service with writes creates in its own migration,
 * because every service owns its own schema and none of them reads another's. Deliberately not
 * a JPA entity: an entity living in a shared module has to be scanned into each service's
 * persistence unit, which is a piece of configuration that would have to be right in six
 * places.
 *
 * <p>The claim is written before the handler runs and committed on its own, so that a second
 * request arriving at the same moment sees it. That is the whole concurrency story: the
 * unique constraint decides which of them proceeds, and the loser reads what the winner is
 * doing.
 */
public class IdempotencyStore {

    /** A response that has been seen through to the end, and what it was. */
    public record Recorded(String fingerprint, Integer status, String body, boolean complete) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this request.
     *
     * @return empty if the claim was taken, or what is already there if somebody else has it
     */
    public Optional<Recorded> claim(
            UUID merchantId, String endpoint, String key, String fingerprint) {

        try {
            jdbc.update(
                    "insert into idempotency_record (id, merchant_id, endpoint, "
                            + "idempotency_key, request_fingerprint, created_at) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    merchantId,
                    endpoint,
                    key,
                    fingerprint,
                    java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
            return Optional.empty();
        } catch (DuplicateKeyException taken) {
            return find(merchantId, endpoint, key);
        }
    }

    public Optional<Recorded> find(UUID merchantId, String endpoint, String key) {
        return jdbc
                .query(
                        "select request_fingerprint, status, response_body, completed_at "
                                + "from idempotency_record "
                                + "where merchant_id = ? and endpoint = ? and idempotency_key = ?",
                        (row, index) -> new Recorded(
                                row.getString("request_fingerprint"),
                                row.getObject("status", Integer.class),
                                row.getString("response_body"),
                                row.getTimestamp("completed_at") != null),
                        merchantId,
                        endpoint,
                        key)
                .stream()
                .findFirst();
    }

    /** Records what the handler produced, so a later repeat can be answered with it. */
    public void complete(
            UUID merchantId, String endpoint, String key, int status, String body) {

        jdbc.update(
                "update idempotency_record set status = ?, response_body = ?, completed_at = ? "
                        + "where merchant_id = ? and endpoint = ? and idempotency_key = ?",
                new Object[] {
                    status,
                    body,
                    java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)),
                    merchantId,
                    endpoint,
                    key
                },
                new int[] {
                    Types.INTEGER, Types.VARCHAR, Types.TIMESTAMP,
                    Types.OTHER, Types.VARCHAR, Types.VARCHAR
                });
    }

    /**
     * Gives the key back.
     *
     * <p>Called when the handler did not succeed. A failure is not an outcome worth
     * replaying: a caller retrying after a 500 wants another attempt, not the 500 again, and
     * a caller retrying after a 400 will get the same 400 from the handler anyway.
     */
    public void release(UUID merchantId, String endpoint, String key) {
        jdbc.update(
                "delete from idempotency_record "
                        + "where merchant_id = ? and endpoint = ? and idempotency_key = ? "
                        + "and completed_at is null",
                merchantId,
                endpoint,
                key);
    }
}
