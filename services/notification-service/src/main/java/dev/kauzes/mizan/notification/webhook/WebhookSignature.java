package dev.kauzes.mizan.notification.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How a merchant knows a delivery came from us.
 *
 * <p>HMAC-SHA256 over {@code timestamp + "." + body}, with the shared secret. The timestamp is
 * inside the signed string rather than merely alongside it, because signing only the body would
 * let anybody who recorded one delivery send it again, unchanged, forever — and the signature
 * would still verify. Inside, a replay is only valid for as long as the receiver's tolerance
 * window, which is theirs to choose and is documented as five minutes.
 *
 * <p>Deliberately the same shape as the request signing a merchant's own server uses to call
 * us (MIZ-32), in the other direction. One scheme to explain, one to implement on each side,
 * and a merchant who has already integrated one recognises the other.
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-Mizan-Signature";
    public static final String TIMESTAMP_HEADER = "X-Mizan-Timestamp";

    /**
     * The delivery's own id, the same on every attempt.
     *
     * <p>What lets a merchant who received a delivery and failed to answer recognise the retry
     * as the same thing rather than a second event. At-least-once is the promise here as it is
     * everywhere else on this platform, and this is the handle that makes it survivable.
     */
    public static final String DELIVERY_HEADER = "X-Mizan-Delivery";

    public static final String EVENT_TYPE_HEADER = "X-Mizan-Event-Type";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    /** The value of the signature header for this body at this moment. */
    public static String of(String secret, Instant at, String body) {
        return "sha256=" + HexFormat.of().formatHex(hmac(secret, signedString(at, body)));
    }

    /**
     * Whether a signature is the right one.
     *
     * <p>Here so that this platform's own tests verify deliveries exactly as a merchant would,
     * rather than by comparing against another call to the signing function. A test that signs
     * and then re-signs proves the function is deterministic and nothing else.
     */
    public static boolean matches(String secret, Instant at, String body, String presented) {
        String expected = of(secret, at, body);
        // Constant time. The comparison itself leaks nothing worth having here, and it costs
        // nothing to do properly.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String signedString(Instant at, String body) {
        return at.getEpochSecond() + "." + body;
    }

    private static byte[] hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException("HMAC-SHA256 is not available in this JVM", impossible);
        }
    }
}
