package dev.kauzes.mizan.ledger.account;

/**
 * What kind of account this is, and therefore which way it moves.
 *
 * <p>In double entry, "debit" and "credit" are directions rather than good and bad news. A
 * debit increases an asset and decreases a liability, which is why the same posting means
 * opposite things to two accounts. Putting that here rather than at each posting means a
 * caller never has to say which way an account moves, and cannot say it wrongly.
 *
 * <p>For a payments platform the one worth being clear about is what a merchant's balance is:
 * a liability. The money is the merchant's, held by the platform, so paying it out reduces
 * what the platform owes.
 */
public enum AccountType {

    /** Something the holder has. Cash at the acquirer, money in a bank. Debit increases it. */
    ASSET(true),

    /** Something the holder owes. A merchant's balance, held by the platform. */
    LIABILITY(false),

    /** What is left over. Not used by a merchant's own books, and here for completeness. */
    EQUITY(false),

    /** What was earned. Fees the platform charges. */
    REVENUE(false),

    /** What was spent. Acquirer charges, refunds given as goodwill. */
    EXPENSE(true);

    private final boolean increasesOnDebit;

    AccountType(boolean increasesOnDebit) {
        this.increasesOnDebit = increasesOnDebit;
    }

    /** Whether a debit makes the balance larger. The credit side does the opposite. */
    public boolean increasesOnDebit() {
        return increasesOnDebit;
    }

    /** The side this account normally sits on, for anybody reading a report. */
    public String normalSide() {
        return increasesOnDebit ? "DEBIT" : "CREDIT";
    }
}
