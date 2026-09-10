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
    NOTIFICATION_READ,

    /** See where a merchant has asked to be told, and how deliveries have gone. */
    WEBHOOK_READ,

    /**
     * Register an endpoint, rotate its secret, disable it.
     *
     * <p>Separate from reading, because rotating a secret breaks every receiver still holding
     * the old one, and that is not something a person who only needed to look should be able
     * to do by clicking the wrong thing.
     */
    WEBHOOK_MANAGE,

    /**
     * Rule on a payment risk held for review: release it, or refuse it.
     *
     * <p>The only permission that lets somebody overrule the platform's own judgement. It is
     * deliberately not part of PAYMENT_WRITE: starting a payment and deciding that a suspicious
     * one should go through are different jobs, and the person who does the second should not
     * get it by being able to do the first.
     */
    REVIEW_RULE
}
