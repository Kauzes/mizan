package dev.kauzes.mizan.identity.user;

/**
 * What a user is allowed to do. The set is closed and mirrored by a check constraint on
 * {@code user_role}, so a role that means nothing to the platform cannot be stored.
 *
 * <p>Nothing enforces these yet. MIZ-31 decides what each one may do and where that is
 * checked; this story only records which one a user holds.
 */
public enum Role {

    /** Owns the merchant. The only role that can be the last one standing. */
    OWNER,

    /** Runs the merchant day to day, short of the things only an owner may do. */
    ADMIN,

    /** Reviews payments the risk engine held back. */
    ANALYST,

    /** Reads, and changes nothing. */
    VIEWER
}
