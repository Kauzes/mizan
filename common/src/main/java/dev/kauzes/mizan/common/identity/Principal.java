package dev.kauzes.mizan.common.identity;

/**
 * What kind of caller this is: a person signed in to the console, or a merchant's own server
 * holding an API key.
 *
 * <p>Separate from roles, because it answers a different question. Roles say what a caller may
 * do; this says whether there is a human behind the request. Most endpoints do not care —
 * a payment is a payment whoever sent it — but a few exist precisely to record that somebody
 * looked at something and formed a judgement, and those are worthless if a script can send
 * them.
 */
public enum Principal {

    /** A person, signed in, holding an access token issued to them. */
    USER,

    /** A merchant's own server, holding a key that merchant can copy into a cron job. */
    API_KEY;

    /**
     * What the gateway said, or {@link #API_KEY} if it said nothing this service understands.
     *
     * <p>Deliberately not a refusal and deliberately not {@code USER}. An unrecognised value
     * means a gateway newer than this service, and the safe reading of "I cannot tell whether
     * a person sent this" is that one did not: it can only ever deny something a person is
     * allowed to do, never allow something a script is not.
     */
    public static Principal of(String header) {
        if (header == null) {
            return API_KEY;
        }
        try {
            return valueOf(header.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return API_KEY;
        }
    }
}
