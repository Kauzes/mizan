package dev.kauzes.mizan.notification.webhook;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Somewhere a merchant has asked to be told, and what they want to hear about.
 *
 * <p>The secret is here in its encrypted form and is never handed back. It is returned exactly
 * once, when it is created or rotated, for the same reason an API key is: a secret this
 * platform can show twice is one this platform is storing badly.
 */
@Entity
@Table(name = "webhook_endpoint")
public class WebhookEndpoint {

    /**
     * Assigned here rather than by the database.
     *
     * <p>The signing secret is encrypted bound to this id, so the id has to exist before the
     * row is written. A generated one is only assigned at insert, which would mean writing the
     * row once without its secret — and the column refuses that, correctly.
     */
    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false)
    private String url;

    @Column
    private String description;

    /** Encrypted, and bound to this endpoint's id so it cannot be moved to another row. */
    @Column(nullable = false)
    private String secret;

    @Column(name = "secret_rotated_at", nullable = false)
    private Instant secretRotatedAt;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * The types this endpoint wants.
     *
     * <p>Eager, because an endpoint is never useful without knowing what it subscribes to, and
     * there are only ever a handful.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "webhook_subscription",
            joinColumns = @JoinColumn(name = "endpoint_id"))
    @Column(name = "event_type", nullable = false)
    private Set<String> eventTypes = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpoint() {
        // for JPA
    }

    public WebhookEndpoint(
            UUID merchantId, String url, String description, Set<String> eventTypes) {

        this.id = UUID.randomUUID();
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.url = Objects.requireNonNull(url, "url").trim();
        this.description = description;
        this.eventTypes = new LinkedHashSet<>(eventTypes);
        this.enabled = true;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
        this.secretRotatedAt = createdAt;
    }

    /**
     * Stores the encrypted secret.
     *
     * <p>Takes it already encrypted rather than encrypting here, so that this class never
     * holds a plaintext secret in a field and no accident of logging or serialising can leak
     * one.
     */
    public void secretIs(String encrypted) {
        this.secret = Objects.requireNonNull(encrypted, "encrypted");
        this.secretRotatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = secretRotatedAt;
    }

    public void wants(Set<String> eventTypes) {
        this.eventTypes = new LinkedHashSet<>(eventTypes);
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String url() {
        return url;
    }

    public String description() {
        return description;
    }

    /** The encrypted form. Opening it is the cipher's business and nobody else's. */
    public String encryptedSecret() {
        return secret;
    }

    public Instant secretRotatedAt() {
        return secretRotatedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<String> eventTypes() {
        return Set.copyOf(eventTypes);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Deliberately says nothing about the secret, whatever logs this. */
    @Override
    public String toString() {
        return "WebhookEndpoint[" + url + " " + eventTypes + (enabled ? "" : " disabled") + "]";
    }
}
