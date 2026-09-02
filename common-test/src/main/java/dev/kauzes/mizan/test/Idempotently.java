package dev.kauzes.mizan.test;

import java.util.UUID;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Sends an {@code Idempotency-Key}, which every write that would be harmful to repeat now
 * requires.
 *
 * <p>Two forms, and the difference matters. A fresh key is what a test wants when it is
 * making a request it has not made before; a named one is what it wants when it is deliberately
 * making the same request twice, which is the behaviour worth testing.
 */
public final class Idempotently {

    private Idempotently() {
    }

    /** A key nothing else has used, for a request that is genuinely new. */
    public static RequestPostProcessor freshKey() {
        return key(UUID.randomUUID().toString());
    }

    /** A key of the test's choosing, for when it means to send the same request again. */
    public static RequestPostProcessor key(String key) {
        return request -> {
            request.addHeader("Idempotency-Key", key);
            return request;
        };
    }
}
