package dev.kauzes.mizan.common.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * How one Mizan service proves to another that it is not a merchant.
 *
 * <p>Some things a service does on a merchant's behalf are things the merchant may not do
 * themselves. Capturing a payment moves money between the merchant's books and the platform's,
 * and no merchant-scoped endpoint may write an entry like that — otherwise a merchant could
 * credit themselves from the platform's clearing account by calling the API they already have.
 *
 * <p>So the endpoints that can do it are not merchant endpoints at all. They live outside
 * {@code /api/}, they are not routed to from the edge, and they require this credential, which
 * only the platform's own services hold. Three separate things have to be wrong before a
 * merchant reaches one.
 *
 * <p>This is a shared secret, which is the weakest of the credible options and the honest one
 * for a platform whose services already share a deployment and a trust boundary. It is not a
 * caller identity: it says "a Mizan service", not which one, and it grants nothing on its own
 * — the merchant an internal call acts for is still named in the request and still checked.
 */
public final class ServiceCredential {

    /** Not an Authorization header: this is not a caller, and must never be read as one. */
    public static final String HEADER = "X-Mizan-Service-Token";

    private ServiceCredential() {
    }

    /**
     * Compares in constant time.
     *
     * <p>An ordinary comparison returns as soon as two bytes differ, and how long it took is
     * something an attacker can measure. That is a small leak here and a free one to close.
     */
    public static boolean matches(String presented, String expected) {
        if (presented == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
