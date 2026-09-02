package dev.kauzes.mizan.banksim;

/** What the acquirer decided about an authorization. */
public enum AuthorizationOutcome {

    /** The money is there and reserved. */
    APPROVED,

    /** The issuer refused, and said why in the reason. */
    DECLINED
}
