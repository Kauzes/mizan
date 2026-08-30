package dev.kauzes.mizan.identity.token;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a sign in or a refresh returns.
 *
 * <p>The lifetimes are reported in seconds so a client can schedule a refresh without
 * parsing the token, which is the only reason a client would ever look inside one.
 */
@Schema(description = "An access token and the refresh token that renews it")
public record TokenPair(
        @Schema(description = "Send as: Authorization: Bearer <token>") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Seconds until the access token expires") long expiresIn,
        @Schema(description = "Presented once, to the refresh endpoint. Rotates on use.")
                String refreshToken,
        @Schema(description = "Seconds until the refresh token expires") long refreshExpiresIn) {

    static TokenPair bearer(
            String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
        return new TokenPair(accessToken, "Bearer", expiresIn, refreshToken, refreshExpiresIn);
    }

    /** Neither token belongs in a log line. */
    @Override
    public String toString() {
        return "TokenPair[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
