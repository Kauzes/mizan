package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One attempt to take money, from the moment somebody says they are going to until it has
 * either happened or definitely has not.
 *
 * <p>Every change of state goes through {@link #moveTo}, which is the only place that knows
 * how to refuse one. A payment cannot be moved by setting a field.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(updatable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * What stops two resolutions writing two answers. The sweep and a caller's own retry can
     * reach one payment at the same moment, and only one of them may decide it.
     */
    @jakarta.persistence.Version
    @Column(nullable = false)
    private long version;

    /** The acquirer's own reference for the authorization, once there is one. */
    @Column(name = "acquirer_reference")
    private String acquirerReference;

    /** All that is kept of the card, and all a person needs to recognise the payment. */
    @Column(name = "card_last_four")
    private String cardLastFour;

    /** What the acquirer said when it refused, so the merchant can be told why. */
    @Column(name = "decline_reason")
    private String declineReason;

    /**
     * The entry in the ledger that records the money moving, once it has.
     *
     * <p>Null until then, and null forever on a payment that was voided: a void releases a
     * reservation, and a reservation was never a movement. The database will not let a
     * captured payment leave this empty.
     */
    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    /**
     * What has been given back so far.
     *
     * <p>Kept here rather than summed from the refunds on every request, because it is the
     * number the limit is checked against and it has to be read under a lock. Summing would
     * mean locking every refund row instead of one payment row.
     */
    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @OrderBy("at asc")
    private List<PaymentTransition> history = new ArrayList<>();

    protected Payment() {
        // for JPA
    }

    public Payment(UUID merchantId, Money amount, String reference, String description) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.amount = Objects.requireNonNull(amount, "amount").amount();
        this.currency = amount.currency().getCurrencyCode();
        this.reference = Objects.requireNonNull(reference, "reference");
        this.description = description;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
        this.history.add(new PaymentTransition(this, null, PaymentStatus.CREATED, null));
    }

    /**
     * Moves the payment, or refuses to.
     *
     * <p>The refusal names both states, because "that is not allowed" tells whoever reads it
     * nothing they can act on, and the two states together usually explain the whole
     * misunderstanding.
     */
    public void moveTo(PaymentStatus next, String reason) {
        if (!status.canMoveTo(next)) {
            throw new UnprocessableException(
                    "A payment that is "
                            + status
                            + " cannot become "
                            + next
                            + (status.isFinal()
                                    ? ". That is where this payment ends."
                                    : ". It can only become " + status.next() + "."));
        }

        history.add(new PaymentTransition(this, status, next, reason));
        this.status = next;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Records that the acquirer was asked and did not answer.
     *
     * <p>Not a refusal, and not a failure of the payment: a failure of the answer to arrive.
     */
    public void outcomeUnknown(String because) {
        moveTo(PaymentStatus.AUTHORIZATION_UNKNOWN, because);
    }

    /** Records an approval. The money is reserved; nothing has moved and nothing is posted. */
    public void authorized(String acquirerReference, String cardLastFour) {
        this.acquirerReference = acquirerReference;
        this.cardLastFour = cardLastFour;
        moveTo(PaymentStatus.AUTHORIZED, null);
    }

    /** Records a refusal, keeping the acquirer's reason rather than inventing one. */
    public void declined(String acquirerReference, String cardLastFour, String reason) {
        this.acquirerReference = acquirerReference;
        this.cardLastFour = cardLastFour;
        this.declineReason = reason;
        moveTo(PaymentStatus.DECLINED, reason);
    }

    /**
     * Records that the money has been taken and where the books say so.
     *
     * <p>The entry comes first and the state second, and that order is the whole point. A
     * payment that says captured with nothing in the books is a lie somebody has to find; an
     * entry with a payment still saying authorized is a retry away from being finished, and
     * the retry is safe because the entry carries the payment's own reference.
     */
    public void captured(UUID ledgerEntryId) {
        this.ledgerEntryId = Objects.requireNonNull(ledgerEntryId, "ledgerEntryId");
        moveTo(PaymentStatus.CAPTURED, null);
    }

    /**
     * Records that the reservation has been released.
     *
     * <p>Nothing is posted. No money moved, so there is nothing for the books to say, and an
     * entry that recorded a movement of zero would be a record of something that did not
     * happen.
     */
    public void voided(String because) {
        moveTo(PaymentStatus.VOIDED, because);
    }

    public UUID ledgerEntryId() {
        return ledgerEntryId;
    }

    public long refundedAmount() {
        return refundedAmount;
    }

    /** What could still be given back. Zero once the whole capture has been refunded. */
    public long refundableAmount() {
        return status == PaymentStatus.CAPTURED ? amount - refundedAmount : 0;
    }

    /**
     * Records that some of the money has gone back.
     *
     * <p>Refuses to give back more than was taken, and refuses to give back anything at all
     * from a payment whose money never moved. Both are checked again by the database, which
     * is what holds if this is ever wrong.
     */
    public void refunded(long amount) {
        if (status != PaymentStatus.CAPTURED) {
            throw new UnprocessableException(
                    "A payment that is "
                            + status
                            + " cannot be refunded. Only money that was captured can be given "
                            + "back; releasing a reservation is a void.");
        }
        if (amount <= 0) {
            throw new UnprocessableException("A refund has to be for more than nothing.");
        }
        if (amount > refundableAmount()) {
            throw new UnprocessableException(
                    "Only "
                            + refundableAmount()
                            + " of this payment is left to refund, and "
                            + amount
                            + " was asked for.");
        }

        this.refundedAmount += amount;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String acquirerReference() {
        return acquirerReference;
    }

    public String cardLastFour() {
        return cardLastFour;
    }

    public String declineReason() {
        return declineReason;
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money money() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public PaymentStatus status() {
        return status;
    }

    public String reference() {
        return reference;
    }

    public String description() {
        return description;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<PaymentTransition> history() {
        return List.copyOf(history);
    }

    @Override
    public String toString() {
        return "Payment[" + reference + " " + amount + " " + currency + " " + status + "]";
    }
}
