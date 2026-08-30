package dev.kauzes.mizan.identity.token;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Credentials exchanged for a token pair")
public record SignInRequest(
        @Schema(example = "owner@kauzes.dev") @NotBlank String email,
        @Schema(format = "password") @NotBlank String password) {

    /** Whatever logs a sign in attempt, it does not log the password. */
    @Override
    public String toString() {
        return "SignInRequest[email=" + email + ", password=***]";
    }
}
