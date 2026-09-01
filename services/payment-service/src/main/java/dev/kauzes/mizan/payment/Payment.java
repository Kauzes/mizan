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
