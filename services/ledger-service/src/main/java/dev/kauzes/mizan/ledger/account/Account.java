package dev.kauzes.mizan.ledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * One account in the books.
 *
 * <p>Nothing here can be changed after it is created. An account's currency and type decide
 * what every posting against it means, so changing either would silently reinterpret history
 * that has already been written.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    /** Null for the platform's own accounts, which belong to no merchant. */
    @Column(name = "merchant_id", updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType type;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The signed sum of this account's postings, debit positive, kept here so that reading a
     * balance is one row rather than a walk through every movement the account ever had.
     *
     * <p>Not flipped to read naturally for a liability. One number with one meaning; the
     * type says which way to read it.
     */
    @Column(nullable = false)
    private long balance;

    /**
     * What stops a lost update. Two writers that both read this balance and both write it
     * back would otherwise leave one of the two movements out of it; the second write is
     * refused instead, and the caller's transaction is retried.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Account() {
        // for JPA
    }

    public Account(UUID merchantId, String code, String name, AccountType type, Currency currency) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.currency = Objects.requireNonNull(currency, "currency").getCurrencyCode();
        this.createdAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public AccountType type() {
        return type;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long balance() {
        return balance;
    }

    public long version() {
        return version;
    }

    /** Moves the balance by one posting, in the same transaction as the posting itself. */
    public void apply(long amount) {
        this.balance = Math.addExact(this.balance, amount);
    }

    /** The platform's own accounts belong to nobody and are not reachable under a merchant. */
    public boolean isPlatformAccount() {
        return merchantId == null;
    }

    @Override
    public String toString() {
        return "Account[code=" + code + ", type=" + type + ", currency=" + currency + "]";
    }
}
