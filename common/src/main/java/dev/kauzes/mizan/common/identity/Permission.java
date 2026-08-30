package dev.kauzes.mizan.common.identity;

/**
 * One thing a caller may do. Roles are made of these, so what a role means is a list rather
 * than a scattering of checks that have to be read to be understood.
 *
 * <p>Only the permissions the platform can actually exercise are here. An epic that adds
 * endpoints adds the permissions they need and grants them in {@link Role}, which is why that
 * table is the one place to look when asking what a role can do.
 */
public enum Permission {

    /** Read the merchant's own account. */
    MERCHANT_READ,

    /** See who acts for the merchant. */
    USER_READ,

    /** Add or remove a user. */
    USER_MANAGE,

    /** Change which roles a user holds. */
    ROLE_MANAGE
}
