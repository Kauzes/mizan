package dev.kauzes.mizan.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One step a payment took. Written when it happens and never afterwards, because a payment
 * that ends somewhere unexpected is investigated through these rows.
 */
@Entity
@Table(name = "payment_transition")
public class PaymentTransition {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    /** Null for the first step: a payment does not come from anywhere. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private PaymentStatus from;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private PaymentStatus to;

    @Column(updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant at;

    protected PaymentTransition() {
        // for JPA
    }

    PaymentTransition(Payment payment, PaymentStatus from, PaymentStatus to, String reason) {
        this.payment = payment;
        this.from = from;
        this.to = to;
        this.reason = reason;
        this.at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }

    public String reason() {
        return reason;
    }

    public Instant at() {
        return at;
    }
}
