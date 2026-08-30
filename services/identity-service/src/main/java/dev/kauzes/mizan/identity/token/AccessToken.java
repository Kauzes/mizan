package dev.kauzes.mizan.identity.token;

import dev.kauzes.mizan.identity.user.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What a verified access token says. Everything a request needs to be authorised is in here,
 * which is the point of signing it: no service has to ask identity who the caller is.
 */
public record AccessToken(
        UUID userId, UUID merchantId, Set<Role> roles, Instant issuedAt, Instant expiresAt) {
}
