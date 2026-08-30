package dev.kauzes.mizan.identity.token;

import dev.kauzes.mizan.identity.user.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token. The token itself was handed to the caller and is not here: what
 * is stored is a digest of it, so a copy of this table is not a set of usable credentials.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    /** Every token descended from one sign in shares this. Replay revokes all of them. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "spent_at")
    private Instant spentAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
        // for JPA
    }

    RefreshToken(
            UUID familyId, UserAccount user, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.familyId = familyId;
        this.user = user;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    UUID familyId() {
        return familyId;
    }

    UserAccount user() {
        return user;
    }

    boolean isSpent() {
        return spentAt != null;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean hasExpiredBy(Instant moment) {
        return !expiresAt.isAfter(moment);
    }

    void spend(Instant moment) {
        this.spentAt = moment;
    }
}
