package dev.kauzes.mizan.ledger.integrity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the ledger says about itself when asked to prove it has not drifted.
 *
 * <p>Three questions, asked separately on purpose: entry by entry, account by account, and
 * currency by currency. They can disagree, and which one fails says where the bug is. An entry
 * that does not balance is a writer that produced a bad entry; an account that disagrees with
 * its postings is the kept balance having drifted from the postings behind it; a currency that
 * does not sum to zero with every entry and account sound is money that arrived from outside
 * the system. One combined answer would tell somebody the ledger is broken and nothing about
 * where to look.
 *
 * <p>A failing check names what disagreed and by how much, because "the ledger is broken" is
 * not something anybody can act on at three in the morning. And it always says what it looked
 * at: a report that only said "sound" would say it just as confidently about an empty database.
 */
public record IntegrityReport(
        boolean sound,
        String summary,
        Instant checkedAt,
        /** What it read to arrive at that, so "sound" can never be a statement about nothing. */
        Examined examined,
        long tookMillis,
        List<CurrencyTotal> totals,
        List<Unbalanced> entries,
        List<Drifted> drifted) {

    /**
     * How much of the ledger this answer is about.
     *
     * <p>Not decoration. Every check here passes trivially over an empty table, so a caller
     * that wants to know the ledger is sound has to be able to see that there was a ledger.
     */
    public record Examined(long entries, long postings, long accounts, long currencies) {

        public boolean anything() {
            return entries > 0 && postings > 0;
        }
    }

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
     * An entry whose own postings do not sum to zero, or that has fewer than two of them.
     *
     * <p>Checked per currency, because an entry may touch more than one and each side has to
     * balance on its own: two currencies netting to zero together is arithmetic on exchange
     * rates that nobody wrote down.
     */
    public record Unbalanced(
            UUID entryId,
            UUID merchantId,
            String reference,
            String currency,
            long postings,
            /** What the postings came to, which for a sound entry is zero. */
            long outBy) {
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
            Instant checkedAt,
            Examined examined,
            long tookMillis,
            List<CurrencyTotal> totals,
            List<Unbalanced> entries,
            List<Drifted> drifted) {

        boolean sound = entries.isEmpty()
                && drifted.isEmpty()
                && totals.stream().allMatch(CurrencyTotal::balances);

        return new IntegrityReport(
                sound,
                summarise(sound, examined, totals, entries, drifted),
                checkedAt,
                examined,
                tookMillis,
                totals,
                entries,
                drifted);
    }

    /** One line, for a log or an alert that has no room for the rest. */
    private static String summarise(
            boolean sound,
            Examined examined,
            List<CurrencyTotal> totals,
            List<Unbalanced> entries,
            List<Drifted> drifted) {

        if (sound) {
            return "the ledger balances in every currency and every balance agrees with its "
                    + "postings, over " + examined.entries() + " entries and "
                    + examined.postings() + " postings";
        }

        List<String> wrong = new ArrayList<>();
        totals.stream()
                .filter(total -> !total.balances())
                .forEach(total -> wrong.add(total.currency() + " out by " + total.total()));

        if (!entries.isEmpty()) {
            wrong.add(entries.size() + " entry/entries do not balance");
        }
        if (!drifted.isEmpty()) {
            wrong.add(drifted.size() + " account(s) disagree with their postings");
        }

        return "the ledger has drifted: " + String.join("; ", wrong);
    }
}
