package dev.kauzes.mizan.identity.token;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A refresh token being exchanged for a new pair.
 *
 * <p>The token is no longer required here, and the body itself is optional: a browser holds
 * its refresh token in a cookie it cannot read, so it has nothing to put in a field. Anything
 * else still sends it, and what is sent wins over what is in a cookie — a client that named a
 * token meant that one.
 */
@Schema(description = "A refresh token being exchanged for a new pair")
public record RefreshRequest(
        @Schema(
                        description =
                                "Optional. Omit it in a browser, where the refresh token is in "
                                        + "a cookie this page cannot read.")
                String refreshToken) {

    /** A refresh token is a credential for days. It is not printed either. */
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=***]";
    }
}
