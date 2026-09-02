package dev.kauzes.mizan.banksim;

/** What has become of an approved authorization since. */
public enum AuthorizationState {

    /** Approved and reserved. The money has not moved. */
    HELD,

    /** Taken. */
    CAPTURED,

    /** Released without being taken. */
    VOIDED,

    /** It was refused, so there is nothing to take or release. */
    REFUSED
}
