package dev.kauzes.mizan.identity;

import dev.kauzes.mizan.common.identity.CallerIdentity;
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
 */
public final class Callers {

    private Callers() {
    }

    public static RequestPostProcessor as(UUID userId, UUID merchantId, Role... roles) {
        String named = Arrays.stream(roles).map(Enum::name).collect(Collectors.joining(","));

        return request -> {
            request.addHeader(CallerIdentity.USER_HEADER, userId.toString());
            request.addHeader(CallerIdentity.MERCHANT_HEADER, merchantId.toString());
            request.addHeader(CallerIdentity.ROLES_HEADER, named);
            return request;
        };
    }

    /** An owner of the given merchant, which is what most tests need. */
    public static RequestPostProcessor owner(UUID userId, UUID merchantId) {
        return as(userId, merchantId, Role.OWNER);
    }
}
