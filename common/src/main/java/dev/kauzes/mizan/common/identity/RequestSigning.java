package dev.kauzes.mizan.common.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How a merchant's server proves a request is theirs.
 *
 * <p>A person signed in to the console carries a token issued to them. A server carries a key
 * and a signature instead, because a long lived bearer credential is one packet capture away
 * from being somebody else's, and because a signature can cover what the request actually
 * says rather than merely who sent it.
 *
 * <p>The canonical form is four lines, joined with {@code \n}:
 *
 * <pre>
 * POST
 * /api/v1/payments
 * 1788100000
 * e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
 * </pre>
 *
 * <p>Method, then path, then the unix second the request was signed, then the SHA-256 of the
 * body in lowercase hex — the digest of no body at all for a GET. Each line is there for a
 * reason: without the method or the path a captured signature could be aimed at a different
 * endpoint, without the body it could be replayed with different numbers in it, and without
 * the timestamp it could be replayed at all.
 *
 * <p>The signature is {@code HMAC-SHA256} of that string under the key's secret, in lowercase
 * hex. This class is the definition, and both the platform and a merchant's client should be
 * able to be written from it alone.
 */
public final class RequestSigning {

    /** Names the key being used. Public, and not a secret. */
    public static final String KEY_HEADER = "X-Mizan-Key";

    /** The signature over the canonical request, lowercase hex. */
    public static final String SIGNATURE_HEADER = "X-Mizan-Signature";

    /** Unix seconds. What keeps a captured request from working tomorrow. */
    public static final String TIMESTAMP_HEADER = "X-Mizan-Timestamp";

    private static final String HMAC = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RequestSigning() {
    }

    public static String canonicalRequest(
            String method, String path, long timestamp, String bodyHash) {

        return method.toUpperCase(java.util.Locale.ROOT)
                + "\n"
                + path
                + "\n"
                + timestamp
                + "\n"
                + bodyHash;
    }

    /** The SHA-256 of the body, lowercase hex. An empty body still has one. */
    public static String bodyHash(byte[] body) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
        }
    }

    public static String sign(String secret, String canonicalRequest) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return hex(mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("HMAC-SHA256 is not available in this JVM", impossible);
        }
    }

    /**
     * Compares two signatures without letting how long it took say how much of the guess was
     * right. A comparison that stops at the first wrong character leaks the signature one
     * character at a time to somebody patient enough to measure.
     */
    public static boolean matches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
