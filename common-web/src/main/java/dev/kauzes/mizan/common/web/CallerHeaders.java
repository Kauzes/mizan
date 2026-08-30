package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.identity.Caller;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * Reads the caller the gateway established off the request.
 *
 * <p>Absent headers mean the request did not come through the gateway. That is a request
 * nobody has authenticated, so it is refused rather than treated as anonymous: services are
 * published locally for debugging, and a debugging port should not be a way past the front
 * door.
 */
final class CallerHeaders {

    private CallerHeaders() {
    }

    static Caller of(HttpServletRequest request) {
        UUID userId = uuid(request.getHeader(CallerIdentity.USER_HEADER));
        UUID merchantId = uuid(request.getHeader(CallerIdentity.MERCHANT_HEADER));

        if (userId == null || merchantId == null) {
            throw new UnauthorizedException("This endpoint needs an access token.");
        }

        return new Caller(
                userId,
                merchantId,
                Caller.rolesIn(request.getHeader(CallerIdentity.ROLES_HEADER)));
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAnId) {
            throw new UnauthorizedException("This endpoint needs an access token.");
        }
    }
}
