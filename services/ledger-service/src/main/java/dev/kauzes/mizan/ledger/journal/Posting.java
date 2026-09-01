package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.money.Money;
import dev.kauzes.mizan.ledger.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One side of a movement: an amount against an account.
 *
 * <p>The amount is signed, and a positive amount is a debit. Whether that makes the account
 * larger is the account type's business, not this one's, which is what lets an entry be
 * checked by simple arithmetic: every entry sums to zero.
 *
 * <p>There is no currency here. A posting is in the currency of the account it names, which
 * is the only arrangement in which the two cannot disagree.
 */
@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private JournalEntry entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(nullable = false, updatable = false)
    private long amount;

    protected Posting() {
        // for JPA
    }

    Posting(JournalEntry entry, Account account, long amount) {
        this.entry = entry;
        this.account = account;
        this.amount = amount;
    }

    public UUID id() {
        return id;
    }

    public Account account() {
        return account;
    }

    public long amount() {
        return amount;
    }

    /** The amount as money, in the currency the account decides. */
    public Money money() {
        return Money.of(amount, account.currency());
    }

    public boolean isDebit() {
        return amount > 0;
    }

    public String direction() {
        return isDebit() ? "DEBIT" : "CREDIT";
    }

    @Override
    public String toString() {
        return "Posting[" + direction() + " " + Math.abs(amount) + " " + account.code() + "]";
    }
}
