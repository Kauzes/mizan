package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.identity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A user as the outside world sees one. There is no field here for the password or its hash,
 * which is how the promise that neither is ever returned is kept: not by remembering to strip
 * them, but by there being nowhere to put them.
 */
@Schema(description = "A person acting for a merchant")
public record UserResponse(
        UUID id, UUID merchantId, String email, String fullName, Set<Role> roles, Instant createdAt) {

    public static UserResponse of(UserAccount user) {
        return new UserResponse(
                user.id(),
                user.merchantId(),
                user.email(),
                user.fullName(),
                user.roles(),
                user.createdAt());
    }
}
