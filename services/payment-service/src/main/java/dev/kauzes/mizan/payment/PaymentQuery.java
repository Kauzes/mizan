package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.UnprocessableException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What a merchant asked to see, once it has been bounded.
 *
 * <p>Every field here arrived as text a caller typed, which makes this the place the platform
 * decides how expensive somebody else is allowed to make its database. A filter is a query, and
 * a query a caller composes is a query a caller can compose badly — so the page size has a
 * ceiling, the offset has a ceiling, and both are here rather than in whichever handler
 * remembered.
 *
 * @param statuses empty means every status, which is not the same as none
 * @param verdicts what risk decided, if the merchant cares
 * @param currency what an amount range is denominated in. Without it the range compares
 *     minor units across currencies, which is arithmetic on two different things
 * @param minAmount inclusive, in minor units, because that is what the platform transports
 * @param maxAmount inclusive
 * @param from inclusive, against when the payment was created
 * @param to exclusive, so a day is [midnight, next midnight) and nothing lands twice
 * @param reference the merchant's own name for a payment, matched exactly
 * @param page zero based
 * @param size how many, bounded
 */
public record PaymentQuery(
        Set<PaymentStatus> statuses,
        Set<String> verdicts,
        String currency,
        Long minAmount,
        Long maxAmount,
        Instant from,
        Instant to,
        String reference,
        int page,
        int size) {

    /**
     * What an unasked question returns.
     *
     * <p>Fifty rather than everything. Returning every payment a merchant has ever taken is
     * what this endpoint used to do, and it worked only because no merchant here has many yet
     * — the kind of bug that is invisible until the day it is an outage.
     */
    public static final int DEFAULT_SIZE = 50;

    /** More than this in one answer is somebody exporting, which is a different endpoint. */
    public static final int MAX_SIZE = 200;

    /**
     * How far in somebody may page.
     *
     * <p>An offset is read by counting past every row before it, so page ten thousand costs
     * the database everything it skipped. A merchant genuinely looking at their two hundred
     * thousandth payment wants a filter, not a page number, and this refusal says so instead
     * of quietly taking a second per request.
     */
    public static final int MAX_OFFSET = 10_000;

    public PaymentQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        verdicts = verdicts == null ? Set.of() : Set.copyOf(verdicts);
        currency = currency == null || currency.isBlank()
                ? null
                : currency.trim().toUpperCase(java.util.Locale.ROOT);
        reference = reference == null || reference.isBlank() ? null : reference.trim();
    }

    /**
     * Reads what a caller sent, refusing what cannot be answered rather than guessing.
     *
     * <p>A filter nobody understood is worse than a refusal: it silently widens the answer, and
     * a merchant looking for one payment is shown a page of others and concludes the platform
     * lost it.
     */
    public static PaymentQuery from(
            List<String> statuses,
            List<String> verdicts,
            String currency,
            Long minAmount,
            Long maxAmount,
            Instant from,
            Instant to,
            String reference,
            Integer page,
            Integer size) {

        int wanted = size == null ? DEFAULT_SIZE : size;
        if (wanted < 1 || wanted > MAX_SIZE) {
            throw new UnprocessableException(
                    "A page holds between 1 and " + MAX_SIZE + " payments.");
        }

        int wantedPage = page == null ? 0 : page;
        if (wantedPage < 0) {
            throw new UnprocessableException("Pages are counted from zero.");
        }
        if ((long) wantedPage * wanted > MAX_OFFSET) {
            throw new UnprocessableException(
                    "This platform pages "
                            + MAX_OFFSET
                            + " payments deep. Further in than that, narrow the search rather "
                            + "than turning pages: an offset is read by counting past every "
                            + "row before it.");
        }

        if (currency != null && !currency.isBlank() && currency.trim().length() != 3) {
            throw new UnprocessableException("A currency is three letters.");
        }
        if (minAmount != null && maxAmount != null && minAmount > maxAmount) {
            throw new UnprocessableException(
                    "The smallest amount asked for is larger than the largest.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new UnprocessableException("That range starts after it ends.");
        }

        return new PaymentQuery(
                known(statuses),
                verdictsIn(verdicts),
                currency,
                minAmount,
                maxAmount,
                from,
                to,
                reference,
                wantedPage,
                wanted);
    }

    /** Whether this asks for anything at all, which decides how the count is done. */
    public boolean isEverything() {
        return statuses.isEmpty()
                && verdicts.isEmpty()
                && currency == null
                && minAmount == null
                && maxAmount == null
                && from == null
                && to == null
                && reference == null;
    }

    public int offset() {
        return page * size;
    }

    private static Set<PaymentStatus> known(List<String> asked) {
        if (asked == null || asked.isEmpty()) {
            return Set.of();
        }
        return asked.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(PaymentQuery::statusNamed)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static PaymentStatus statusNamed(String name) {
        try {
            return PaymentStatus.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new UnprocessableException(
                    "There is no payment status called "
                            + name
                            + ". The ones there are: "
                            + java.util.Arrays.toString(PaymentStatus.values()));
        }
    }

    private static Set<String> verdictsIn(List<String> asked) {
        if (asked == null || asked.isEmpty()) {
            return Set.of();
        }
        return asked.stream()
                .map(String::trim)
                .filter(verdict -> !verdict.isEmpty())
                .map(verdict -> verdict.toUpperCase(java.util.Locale.ROOT))
                .peek(PaymentQuery::refuseUnknownVerdict)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The verdicts a payment can carry, which is risk's set plus one.
     *
     * <p>UNAVAILABLE is a real verdict here rather than an absence: it is what a payment
     * carries when the scorer could not be asked, and a merchant reviewing a day of those is
     * exactly the person who needs to filter for them.
     */
    private static void refuseUnknownVerdict(String verdict) {
        if (!Set.of("APPROVE", "REVIEW", "BLOCK", "UNAVAILABLE").contains(verdict)) {
            throw new UnprocessableException(
                    "There is no risk verdict called "
                            + verdict
                            + ". The ones there are: APPROVE, REVIEW, BLOCK, UNAVAILABLE.");
        }
    }
}
