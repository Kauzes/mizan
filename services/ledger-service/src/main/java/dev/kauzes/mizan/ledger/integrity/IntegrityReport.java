package dev.kauzes.mizan.ledger.integrity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the ledger says about itself when asked to prove it has not drifted.
 *
 * <p>A failing check names what disagreed and by how much, because "the ledger is broken" is
 * not something anybody can act on at three in the morning.
 */
public record IntegrityReport(
        boolean sound,
        String summary,
        Instant checkedAt,
        List<CurrencyTotal> totals,
        List<Drifted> drifted) {

    /**
     * The sum of every posting in the system in one currency. Money is only ever moved from
     * somewhere to somewhere, so this is zero or something is wrong.
     */
    public record CurrencyTotal(String currency, long total, long accounts, long postings) {

        public boolean balances() {
            return total == 0L;
        }
    }

    /**
     * An account whose kept balance disagrees with the postings behind it.
     *
     * <p>This is the drift no other test can see, because everywhere else the balance and the
     * postings are written by the same code in the same transaction, and a bug that wrote
     * both wrongly would look right to all of them.
     */
    public record Drifted(
            UUID accountId,
            UUID merchantId,
            String code,
            String currency,
            long keptBalance,
            long postingsTotal,
            /** Stated rather than left to be worked out, since it is what somebody acts on. */
            long outBy) {

        public static Drifted of(
                UUID accountId,
                UUID merchantId,
                String code,
                String currency,
                long keptBalance,
                long postingsTotal) {

            return new Drifted(
                    accountId,
                    merchantId,
                    code,
                    currency,
                    keptBalance,
                    postingsTotal,
                    keptBalance - postingsTotal);
        }
    }

    public static IntegrityReport of(
            Instant checkedAt, List<CurrencyTotal> totals, List<Drifted> drifted) {

        boolean sound = drifted.isEmpty() && totals.stream().allMatch(CurrencyTotal::balances);
        return new IntegrityReport(sound, summarise(sound, totals, drifted), checkedAt, totals,
                drifted);
    }

    /** One line, for a log or an alert that has no room for the rest. */
    private static String summarise(
            boolean sound, List<CurrencyTotal> totals, List<Drifted> drifted) {

        if (sound) {
            return "the ledger balances in every currency and every balance agrees with its "
                    + "postings";
        }

        String unbalanced = totals.stream()
                .filter(total -> !total.balances())
                .map(total -> total.currency() + " out by " + total.total())
                .reduce((first, second) -> first + ", " + second)
                .orElse("");

        return "the ledger has drifted: "
                + (unbalanced.isEmpty() ? "" : unbalanced + "; ")
                + drifted.size()
                + " account(s) disagree with their postings";
    }
}
