package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.identity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Somebody else who acts for this merchant, and what they are allowed to do. */
@Schema(description = "A person to add to a merchant")
public record AddUserRequest(
        @Schema(example = "analyst@kauzes.dev") @NotBlank @Email @Size(max = 320) String email,
        @Schema(format = "password") @NotBlank @Size(min = 12, max = 200) String password,
        @Schema(example = "Alex Kauzes") @NotBlank @Size(max = 200) String fullName,
        @Schema(description = "At least one. A user with no role could sign in and do nothing.")
                @NotEmpty
                Set<Role> roles) {

    @Override
    public String toString() {
        return "AddUserRequest[email=" + email + ", fullName=" + fullName + ", roles=" + roles
                + ", password=***]";
    }
}
