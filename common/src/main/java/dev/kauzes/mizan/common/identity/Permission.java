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
    ROLE_MANAGE,

    /** Issue, rotate and revoke the keys a merchant's own servers authenticate with. */
    API_KEY_MANAGE,

    /** Read the merchant's books: its accounts, and what they hold. */
    ACCOUNT_READ,

    /** Open an account. Not the same as moving money into one. */
    ACCOUNT_MANAGE,

    /** Read what has been posted to the books. */
    ENTRY_READ,

    /** Write to the books. The one permission that moves money. */
    ENTRY_POST,

    /** See a merchant's payments and what has happened to them. */
    PAYMENT_READ,

    /** Start a payment, and move it along: authorize, capture, void. */
    PAYMENT_WRITE,

    /** Read what the platform has decided the merchant should be told, and about what. */
    NOTIFICATION_READ
}
