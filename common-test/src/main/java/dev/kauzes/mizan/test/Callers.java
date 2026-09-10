package dev.kauzes.mizan.test;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Principal;
import dev.kauzes.mizan.common.identity.Role;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Sends the headers the gateway would have set, so a test can be somebody.
 *
 * <p>Deliberately allows sending any merchant and any role, including combinations no token
 * would carry. A service must hold the tenant boundary against whatever reaches it, and a
 * test that could only send well behaved headers could not show that.
 *
 * <p>Shared rather than owned by one service, because every service behind the gateway reads
 * the same headers and every one of them needs to be tested against a caller who is not who
 * they claim.
 */
public final class Callers {

    private Callers() {
    }

    /** A person, signed in, which is what most tests mean by a caller. */
    public static RequestPostProcessor as(UUID userId, UUID merchantId, Role... roles) {
        return as(Principal.USER, userId, merchantId, roles);
    }

    /**
     * A merchant's own server, holding an API key.
     *
     * <p>Its own helper because the difference matters to a handful of endpoints and matters
     * a great deal to them: a review is a judgement somebody formed, and a control a merchant
     * can put in a cron job is not a control.
     */
    public static RequestPostProcessor apiKey(UUID userId, UUID merchantId, Role... roles) {
        return as(Principal.API_KEY, userId, merchantId, roles);
    }

    public static RequestPostProcessor as(
            Principal principal, UUID userId, UUID merchantId, Role... roles) {

        String named = Arrays.stream(roles).map(Enum::name).collect(Collectors.joining(","));

        return request -> {
            request.addHeader(CallerIdentity.USER_HEADER, userId.toString());
            request.addHeader(CallerIdentity.MERCHANT_HEADER, merchantId.toString());
            request.addHeader(CallerIdentity.ROLES_HEADER, named);
            request.addHeader(CallerIdentity.PRINCIPAL_HEADER, principal.name());
            return request;
        };
    }

    /** An owner of the given merchant, which is what most tests need. */
    public static RequestPostProcessor owner(UUID userId, UUID merchantId) {
        return as(userId, merchantId, Role.OWNER);
    }
}
