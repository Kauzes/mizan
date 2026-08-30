package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.merchant.Merchant;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A person acting for a merchant.
 *
 * <p>The email is lowercased on the way in, because the database refuses anything else: an
 * address is one address however it was typed, and uniqueness has to mean that.
 *
 * <p>The stored value is a hash and this class never sees a plaintext password. It has no
 * accessor returning the hash either, so the value cannot reach a response or a log line by
 * something serialising a user.
 */
@Entity
@Table(name = "app_user")
public class UserAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserAccount() {
        // for JPA
    }

    public UserAccount(
            Merchant merchant, String email, String passwordHash, String fullName, Role role) {
        this.merchant = Objects.requireNonNull(merchant, "merchant");
        this.email = normalise(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.roles = EnumSet.of(Objects.requireNonNull(role, "role"));
        this.createdAt = Instant.now();
    }

    /** The form the database will accept, and the only form a lookup should ever search for. */
    public static String normalise(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a candidate password is this user's.
     *
     * <p>The check comes to the hash rather than the hash going to the check, which is what
     * keeps the stored value from having an accessor at all.
     */
    public boolean passwordMatches(CharSequence candidate, PasswordEncoder encoder) {
        return encoder.matches(candidate, passwordHash);
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchant.id();
    }

    public String email() {
        return email;
    }

    public String fullName() {
        return fullName;
    }

    public Set<Role> roles() {
        return Set.copyOf(roles);
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Replaces every role at once, which is what a caller sending a complete set means. */
    public void holdOnly(Set<Role> replacement) {
        this.roles = EnumSet.copyOf(replacement);
    }

    public boolean holds(Role role) {
        return roles.contains(role);
    }

    /** Deliberately says nothing about the password, whatever logs this. */
    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", email=" + email + "]";
    }
}
