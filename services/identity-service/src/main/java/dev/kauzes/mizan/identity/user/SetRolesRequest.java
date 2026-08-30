package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.identity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/** The roles a user should hold afterwards, replacing whatever they hold now. */
@Schema(description = "The complete set of roles a user should hold")
public record SetRolesRequest(@NotEmpty Set<Role> roles) {
}
