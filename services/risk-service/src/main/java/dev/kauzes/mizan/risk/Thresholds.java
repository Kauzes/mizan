package dev.kauzes.mizan.risk;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where a merchant's line sits.
 *
 * <p>Per merchant, because one line for everybody is one line that is wrong for nearly
 * everybody. A merchant with no row of their own uses the platform's defaults, which is the
 * right thing on day one and is not a row pretending to be a decision somebody made.
 */
@Component
public class Thresholds {

    private final JdbcTemplate jdbc;
    private final int defaultReviewAbove;
    private final int defaultBlockAbove;

    public Thresholds(
            JdbcTemplate jdbc,
            @Value("${mizan.risk.review-above:40}") int defaultReviewAbove,
            @Value("${mizan.risk.block-above:70}") int defaultBlockAbove) {

        this.jdbc = jdbc;
        this.defaultReviewAbove = defaultReviewAbove;
        this.defaultBlockAbove = defaultBlockAbove;
    }

    /** This merchant's lines, or the platform's if they have not chosen. */
    public int[] forMerchant(UUID merchantId) {
        return jdbc
                .query(
                        "select review_above, block_above from merchant_thresholds "
                                + "where merchant_id = ?",
                        (row, index) -> new int[] {
                            row.getInt("review_above"), row.getInt("block_above")
                        },
                        merchantId)
                .stream()
                .findFirst()
                .orElseGet(() -> new int[] {defaultReviewAbove, defaultBlockAbove});
    }

    /** Records a merchant's own appetite, and who decided it. */
    public void set(UUID merchantId, int reviewAbove, int blockAbove, String setBy) {
        jdbc.update(
                "insert into merchant_thresholds (merchant_id, review_above, block_above, "
                        + "set_by, updated_at) values (?, ?, ?, ?, now()) "
                        + "on conflict (merchant_id) do update set review_above = excluded.review_above, "
                        + "block_above = excluded.block_above, set_by = excluded.set_by, "
                        + "updated_at = excluded.updated_at",
                merchantId,
                reviewAbove,
                blockAbove,
                setBy);
    }
}
