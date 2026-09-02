package dev.kauzes.mizan.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Money given back, in part or in full.
 *
 * <p>Its own thing rather than a state of the payment. A payment that has been half refunded is
 * still captured — the money moved, and that stays true — so a status that tried to describe
 * both would have to lie about one of them. What the payment keeps is the single number the
 * rules depend on.
 */
@Entity
@Table(name = "refund")
public class Refund {

    /**
     * What happened to it.
     *
     * <p>One value, because a refund exists only once the money has gone back and the books
     * say so: a refund that failed left nothing behind to record. In-flight states arrive in
     * MIZ-52, with the machinery that can resolve them — a state nothing can move a row out of
     * is a state that strands rows.
     */
    public enum Status {
        SUCCEEDED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    /** The merchant's own name for this refund, and what makes sending it again safe. */
    @Column(nullable = false, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Why it failed, or why the merchant asked for it. Whichever there is to say. */
    @Column
    private String reason;

    @Column(name = "acquirer_reference")
    private String acquirerReference;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Version
    @Column(nullable = false)
    private long version;

    protected Refund() {
        // for JPA
    }

    /**
     * A refund that has already happened.
     *
     * <p>Constructed after the acquirer has given the money back and the ledger has recorded
     * it, not before. Writing the row first would mean a row claiming to have succeeded while
     * pointing at neither — which the database refuses, and rightly.
     */
    public Refund(
            Payment payment,
            long amount,
            String reference,
            String reason,
            String acquirerReference,
            UUID ledgerEntryId) {

        this.paymentId = payment.id();
        this.merchantId = payment.merchantId();
        this.amount = amount;
        this.currency = payment.money().currency().getCurrencyCode();
        this.reference = Objects.requireNonNull(reference, "reference");
        this.reason = reason;
        this.acquirerReference = Objects.requireNonNull(acquirerReference, "acquirerReference");
        this.ledgerEntryId = Objects.requireNonNull(ledgerEntryId, "ledgerEntryId");
        this.status = Status.SUCCEEDED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public long amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String reference() {
        return reference;
    }

    public Status status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public String acquirerReference() {
        return acquirerReference;
    }

    public UUID ledgerEntryId() {
        return ledgerEntryId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "Refund[" + reference + " " + amount + " " + currency + " " + status + "]";
    }
}
