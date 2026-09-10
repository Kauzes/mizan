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

    /** How many times resolving an unknown outcome has been tried. */
    @Column(name = "resolve_attempts", nullable = false)
    private int resolveAttempts;

    /**
     * When this payment stopped being something the platform could sort out on its own.
     *
     * <p>Null for the overwhelming majority. A payment here is one where retrying has been
     * tried enough times to conclude it is not working, and a person has to decide.
     */
    @Column(name = "needs_attention_since")
    private Instant needsAttentionSince;

    @Column(name = "attention_reason")
    private String attentionReason;

    /**
     * When a person dealt with it.
     *
     * <p>Separate from the attention flag, because being stuck stays true: nobody could work
     * out what happened, and that remains a fact about the payment. What changes is that
     * somebody has looked, which is a fact about the operator.
     */
    @Column(name = "attention_handled_at")
    private Instant attentionHandledAt;

    /**
     * What risk thought, kept on the payment rather than asked for again.
     *
     * <p>"Why was this held" is asked long after the scorer's view of the world has moved on,
     * and a scorer is a function of what was known at the time — which is exactly what nobody
     * can reconstruct later.
     */
    @Column(name = "risk_verdict")
    private String riskVerdict;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "risk_reasons")
    private String riskReasons;

    @Column(name = "risk_checked_at")
    private Instant riskCheckedAt;

    @Column(name = "held_at")
    private Instant heldAt;

    /**
     * What a person decided about a payment that was held.
     *
     * <p>Beside the payment rather than moving it, because releasing cannot authorize: this
     * service keeps only four digits of the card, so the merchant has to present it again. The
     * payment stays held until they do, and this is what says it is no longer waiting.
     */
    @Column(name = "review_ruling")
    private String reviewRuling;

    @Column(name = "review_ruled_by")
    private String reviewRuledBy;

    @Column(name = "review_ruled_at")
    private Instant reviewRuledAt;

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

    /** Records what risk thought, whatever it was, including that it could not be asked. */
    public void scored(String verdict, Integer score, String reasons, Instant at) {
        this.riskVerdict = verdict;
        this.riskScore = score;
        this.riskReasons = reasons;
        this.riskCheckedAt = at;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Holds the payment for a person, without charging anybody.
     *
     * <p>Not a failure. The customer's money is untouched and the platform has not made its
     * mind up, which is a different thing from having decided against them and has to stay
     * legible as one.
     */
    public void heldForReview(String because) {
        this.heldAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        moveTo(PaymentStatus.HELD_FOR_REVIEW, because);
    }

    /** Released by a person, or expired: either way it stops being held. */
    private void noLongerHeld() {
        this.heldAt = null;
    }

    /**
     * A person has decided this one should go through.
     *
     * <p>Does not authorize it. Releasing says the scorer was wrong about this payment; taking
     * the money is a separate act the merchant performs, with the card they still have and this
     * service does not. Collapsing the two would mean an analyst's click charging a customer.
     */
    public void releasedForReview(String who, String why) {
        this.reviewRuling = "RELEASED";
        this.reviewRuledBy = who;
        this.reviewRuledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.history.add(new PaymentTransition(
                this, status, PaymentStatus.HELD_FOR_REVIEW, who + " released it: " + why));
        this.updatedAt = reviewRuledAt;
    }

    /** And this is where a refusal is recorded, before the payment is declined. */
    public void refusedAtReview(String who) {
        this.reviewRuling = "REFUSED";
        this.reviewRuledBy = who;
        this.reviewRuledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String reviewRuling() {
        return reviewRuling;
    }

    public String reviewRuledBy() {
        return reviewRuledBy;
    }

    public Instant reviewRuledAt() {
        return reviewRuledAt;
    }

    /**
     * Whether this payment is still waiting for a person.
     *
     * <p>Held and unruled. A released one is held and not waiting, which is why the queue and
     * the expiry sweep both ask this rather than asking about the status.
     */
    public boolean isWaitingForAPerson() {
        return status == PaymentStatus.HELD_FOR_REVIEW && reviewRuling == null;
    }

    /** Whether a person has said this one may go through. */
    public boolean wasReleased() {
        return "RELEASED".equals(reviewRuling);
    }

    public String riskVerdict() {
        return riskVerdict;
    }

    public Integer riskScore() {
        return riskScore;
    }

    public String riskReasons() {
        return riskReasons;
    }

    public Instant riskCheckedAt() {
        return riskCheckedAt;
    }

    public Instant heldAt() {
        return heldAt;
    }

    /** Records an approval. The money is reserved; nothing has moved and nothing is posted. */
    public void authorized(String acquirerReference, String cardLastFour) {
        this.acquirerReference = acquirerReference;
        this.cardLastFour = cardLastFour;
        noLongerHeld();
        moveTo(PaymentStatus.AUTHORIZED, null);
    }

    /** Records a refusal, keeping the acquirer's reason rather than inventing one. */
    public void declined(String acquirerReference, String cardLastFour, String reason) {
        this.acquirerReference = acquirerReference;
        this.cardLastFour = cardLastFour;
        this.declineReason = reason;
        noLongerHeld();
        moveTo(PaymentStatus.DECLINED, reason);
    }

    /**
     * Refused by this platform rather than by the acquirer.
     *
     * <p>No acquirer reference, because nobody was contacted. The reason is kept in the same
     * field a decline reason goes in, so a merchant reading "why was this refused" has one
     * place to look — and it says plainly that it was us.
     */
    public void refusedByRisk(String reason) {
        this.declineReason = reason;
        noLongerHeld();
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

    public int resolveAttempts() {
        return resolveAttempts;
    }

    public Instant needsAttentionSince() {
        return needsAttentionSince;
    }

    public String attentionReason() {
        return attentionReason;
    }

    public boolean needsAPerson() {
        return needsAttentionSince != null && attentionHandledAt == null;
    }

    public Instant attentionHandledAt() {
        return attentionHandledAt;
    }

    /** Records one more fruitless attempt to work out what happened. */
    public void resolveAttempted() {
        this.resolveAttempts += 1;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Stops trying, and says so where somebody will see it.
     *
     * <p>Not a state change: the payment is still exactly as unknown as it was. What has
     * changed is that the platform has given up working it out alone, which is a different
     * fact and deserves a different field.
     */
    public void needsAPerson(String because) {
        if (needsAttentionSince == null) {
            this.needsAttentionSince = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        this.attentionReason = because;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A person has looked and decided nothing more is to be done.
     *
     * <p>It stops appearing and stays out of the sweep. Resetting the attempts here instead
     * would put it straight back in front of the resolver, which would ask five more times and
     * strand it again — which is what this did before the smoke check noticed.
     */
    public void attentionClosed() {
        this.attentionHandledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = attentionHandledAt;
    }

    /** A person has decided it is worth another go, so it becomes the platform's problem again. */
    public void attentionRetry() {
        this.needsAttentionSince = null;
        this.attentionReason = null;
        this.attentionHandledAt = null;
        this.resolveAttempts = 0;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
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

    /**
     * Gives back an amount a refund had reserved and did not use.
     *
     * <p>Only ever when the acquirer refused outright, so nothing moved. A refund that merely
     * stopped answering keeps its reservation: the money may be gone, and handing the merchant
     * the headroom back would let them refund it a second time.
     */
    public void refundReleased(long amount) {
        if (amount > refundedAmount) {
            throw new IllegalStateException(
                    "cannot release " + amount + " when only " + refundedAmount + " is reserved");
        }
        this.refundedAmount -= amount;
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
