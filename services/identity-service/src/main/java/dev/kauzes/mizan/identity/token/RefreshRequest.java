package dev.kauzes.mizan.identity.token;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A refresh token being exchanged for a new pair")
public record RefreshRequest(@NotBlank String refreshToken) {

    /** A refresh token is a credential for days. It is not printed either. */
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=***]";
    }
}
