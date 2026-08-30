package dev.kauzes.mizan.identity.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The account money belongs to, and the tenant boundary every later table refers to.
 */
@Entity
@Table(name = "merchant")
public class Merchant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // for JPA
    }

    public Merchant(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.createdAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
