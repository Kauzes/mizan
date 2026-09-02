package dev.kauzes.mizan.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import dev.kauzes.mizan.common.error.UnprocessableException;
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
    private RefundStatus status;

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

    /** How many times finishing this has been tried and failed. */
    @Column(nullable = false)
    private int attempts;

    /** When it may be tried again, so a broken refund is not retried in a loop. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** Why it did not work last time, kept where a person looking at it can read it. */
    @Column(name = "last_error")
    private String lastError;

    @jakarta.persistence.Version
    @Column(nullable = false)
    private long version;

    protected Refund() {
        // for JPA
    }

    /**
     * A refund that has been asked for and not yet done.
     *
     * <p>Written before the acquirer is asked, in its own transaction, so that a process which
     * dies during the call leaves a record of what was attempted rather than nothing at all.
     * MIZ-51 wrote this row only once everything had worked, which was honest for a story with
     * no way to resume and is exactly what this one fixes.
     */
    public Refund(Payment payment, long amount, String reference, String reason) {
        this.paymentId = payment.id();
        this.merchantId = payment.merchantId();
        this.amount = amount;
        this.currency = payment.money().currency().getCurrencyCode();
        this.reference = Objects.requireNonNull(reference, "reference");
        this.reason = reason;
        this.status = RefundStatus.REQUESTED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    /**
     * Moves the refund, or refuses to.
     *
     * <p>Every change of state goes through here, so a refund cannot be moved by setting a
     * field, and a resumption that races the original call cannot walk it backwards.
     */
    private void moveTo(RefundStatus next) {
        if (status == next) {
            // A resumption that arrives after the original call finished the same step. Not an
            // error: at-least-once is the shape of everything that resumes.
            return;
        }
        if (!status.canMoveTo(next)) {
            throw new UnprocessableException(
                    "A refund that is " + status + " cannot become " + next + ".");
        }
        this.status = next;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** The acquirer has given the money back. The books do not know yet. */
    public void returned(String acquirerReference) {
        this.acquirerReference = Objects.requireNonNull(acquirerReference, "acquirerReference");
        moveTo(RefundStatus.RETURNED);
    }

    /** And now they do. */
    public void recorded(UUID ledgerEntryId) {
        this.ledgerEntryId = Objects.requireNonNull(ledgerEntryId, "ledgerEntryId");
        moveTo(RefundStatus.SUCCEEDED);
    }

    /** The acquirer refused outright. Nothing moved, so the reservation is given back. */
    public void failed(String why) {
        this.lastError = trim(why);
        moveTo(RefundStatus.FAILED);
    }

    /**
     * Nobody could finish it, so a person has to look.
     *
     * <p>The reservation stays held. The money may have gone back, and releasing the amount
     * would let it be refunded a second time.
     */
    public void abandoned(String why) {
        this.lastError = trim(why);
        moveTo(RefundStatus.ABANDONED);
    }

    /**
     * Puts this refund out of the running for a while, and says why.
     *
     * @return whether it has now been tried too many times to keep trying
     */
    public boolean attemptFailed(String why, java.time.Duration waitFor, int limit) {
        this.attempts += 1;
        this.lastError = trim(why);
        this.nextAttemptAt = Instant.now().plus(waitFor);
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return this.attempts >= limit;
    }

    private static String trim(String why) {
        if (why == null) {
            return null;
        }
        return why.length() > 1000 ? why.substring(0, 1000) : why;
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

    public RefundStatus status() {
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

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public String toString() {
        return "Refund[" + reference + " " + amount + " " + currency + " " + status + "]";
    }
}
