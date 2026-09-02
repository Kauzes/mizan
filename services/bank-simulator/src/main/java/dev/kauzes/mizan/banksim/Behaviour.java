package dev.kauzes.mizan.banksim;

/**
 * What this acquirer does with a request, chosen by the card it is given.
 *
 * <p>Test cards rather than a switch somebody flips, because the platform has to be able to
 * provoke a decline without knowing it is talking to a simulator. A payment service that had
 * to be told "now behave as though the bank refuses" would be a payment service with a
 * simulation-shaped hole in it, and the hole would be in production too.
 *
 * <p>This is how real acquirers publish their sandboxes, so a merchant integrating against
 * Mizan learns a habit that transfers.
 */
public enum Behaviour {

    /** The ordinary case, and what anything unrecognised does. */
    APPROVE("0000", null, false),

    /** The customer has not got it. */
    DECLINE_INSUFFICIENT_FUNDS("0002", "insufficient_funds", false),

    /** The issuer refused and did not say why, which happens more than anybody would like. */
    DECLINE_DO_NOT_HONOUR("0005", "do_not_honour", false),

    /** The card has been reported. */
    DECLINE_STOLEN("0007", "stolen_card", false),

    /**
     * Takes longer than the caller is prepared to wait. Not a failure: the authorization
     * happens, and the answer arrives after whoever asked has stopped listening, which is
     * exactly the state MIZ-44 has to resolve by asking rather than assuming.
     */
    APPROVE_SLOWLY("0069", null, true),

    /**
     * The same, refused. A caller who gave up waiting has no way of telling this apart from
     * the one above, which is the point: resolving a timeout has to cope with the answer
     * being either, and a catalogue that could only produce one of them would let a resolver
     * that always guessed "approved" pass its tests.
     */
    DECLINE_SLOWLY("0068", "do_not_honour", true);

    private final String lastFour;
    private final String reason;
    private final boolean slow;

    Behaviour(String lastFour, String reason, boolean slow) {
        this.lastFour = lastFour;
        this.reason = reason;
        this.slow = slow;
    }

    public String lastFour() {
        return lastFour;
    }

    /** Why it was refused, or null if it was not. */
    public String reason() {
        return reason;
    }

    public boolean declines() {
        return reason != null;
    }

    public boolean isSlow() {
        return slow;
    }

    /** What this card asks for. Anything not in the catalogue is an ordinary approval. */
    public static Behaviour of(String card) {
        if (card == null || card.length() < 4) {
            return APPROVE;
        }

        String last = card.substring(card.length() - 4);
        for (Behaviour behaviour : values()) {
            if (behaviour.lastFour.equals(last)) {
                return behaviour;
            }
        }
        return APPROVE;
    }
}
