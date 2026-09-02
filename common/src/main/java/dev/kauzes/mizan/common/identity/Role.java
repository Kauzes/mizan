package dev.kauzes.mizan.common.identity;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a user is allowed to do. The set is closed and mirrored by a check constraint on
 * {@code user_role}, so a role that means nothing to the platform cannot be stored.
 *
 * <p>Every role is scoped to one merchant. Nothing here grants anything across the tenant
 * boundary, and no role exists that can: an owner is an owner of their own account and
 * nobody else's.
 *
 * <p>This lives in the shared module because roles arrive at a service on a header from the
 * gateway, so every service reads the same set and the same table of what each one may do.
 */
public enum Role {

    /**
     * Owns the merchant. May do anything to their own account, which is why this is the only
     * role defined as the whole set: an epic that adds a permission grants it to the owner
     * without a decision, and grants it to nobody else without one.
     */
    OWNER(EnumSet.allOf(Permission.class)),

    /**
     * Runs the merchant day to day, short of the things only an owner may do. Adding people
     * and changing what they may do stays with the owner.
     */
    ADMIN(EnumSet.of(
            Permission.MERCHANT_READ,
            Permission.USER_READ,
            Permission.ACCOUNT_READ,
            Permission.ACCOUNT_MANAGE,
            Permission.ENTRY_READ,
            Permission.ENTRY_POST,
            Permission.PAYMENT_READ,
            Permission.PAYMENT_WRITE)),

    /**
     * Reviews payments the risk engine held back. Holds nothing administrative on purpose:
     * somebody deciding whether a payment is fraud has no reason to be able to add a user.
     * Reading the books is part of the job; the rest arrives with the review queue in MIZ-6.
     */
    ANALYST(EnumSet.of(
            Permission.MERCHANT_READ,
            Permission.ACCOUNT_READ,
            Permission.ENTRY_READ,
            Permission.PAYMENT_READ)),

    /** Reads, and changes nothing. */
    VIEWER(EnumSet.of(
            Permission.MERCHANT_READ,
            Permission.ACCOUNT_READ,
            Permission.ENTRY_READ,
            Permission.PAYMENT_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return Set.copyOf(permissions);
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
