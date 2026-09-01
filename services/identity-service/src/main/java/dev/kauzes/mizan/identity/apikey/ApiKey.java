package dev.kauzes.mizan.identity.apikey;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.merchant.Merchant;
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

/** One credential a merchant's server authenticates with. */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(name = "key_id", nullable = false, updatable = false)
    private String keyId;

    @Column(nullable = false)
    private String name;

    @Column(name = "secret_encrypted", nullable = false, updatable = false)
    private String secretEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "rotated_from", updatable = false)
    private UUID rotatedFrom;

    protected ApiKey() {
        // for JPA
    }

    ApiKey(Merchant merchant, String keyId, String name, String secretEncrypted, Role role) {
        this.merchant = merchant;
        this.keyId = keyId;
        this.name = name;
        this.secretEncrypted = secretEncrypted;
        this.role = role;
        this.createdAt = Instant.now();
    }

    UUID id() {
        return id;
    }

    UUID merchantId() {
        return merchant.id();
    }

    Merchant merchant() {
        return merchant;
    }

    String keyId() {
        return keyId;
    }

    String name() {
        return name;
    }

    String secretEncrypted() {
        return secretEncrypted;
    }

    Role role() {
        return role;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }

    Instant revokedAt() {
        return revokedAt;
    }

    UUID rotatedFrom() {
        return rotatedFrom;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    void revoke(Instant moment) {
        if (revokedAt == null) {
            this.revokedAt = moment;
        }
    }

    void used(Instant moment) {
        this.lastUsedAt = moment;
    }

    void replaced(ApiKey previous) {
        this.rotatedFrom = previous.id();
    }

    /** Nothing here prints the secret, encrypted or otherwise. */
    @Override
    public String toString() {
        return "ApiKey[keyId=" + keyId + ", role=" + role + "]";
    }
}
