package dev.kauzes.mizan.identity.apikey;

import dev.kauzes.mizan.common.identity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** The shapes a caller sends and sees when managing keys. */
final class ApiKeyResponses {

    private ApiKeyResponses() {
    }

    @Schema(description = "A key to issue")
    record IssueKeyRequest(
            @Schema(description = "What this key is for", example = "nightly reconciliation")
                    @NotBlank
                    @Size(max = 200)
                    String name,
            @Schema(description = "What the key may do. One role: a server integration does one job.")
                    @NotNull
                    Role role) {
    }

    @Schema(description = "An API key. The secret is not here and cannot be retrieved.")
    record ApiKeyResponse(
            UUID id,
            @Schema(description = "Sent as X-Mizan-Key", example = "mzk_7Qv3nR2xKp0LmA9d")
                    String keyId,
            String name,
            Role role,
            Instant createdAt,
            @Schema(description = "When a request last used it, or null if never")
                    Instant lastUsedAt,
            Instant revokedAt,
            @Schema(description = "The key this one replaced, if it was issued by a rotation")
                    UUID rotatedFrom) {

        static ApiKeyResponse of(ApiKey key) {
            return new ApiKeyResponse(
                    key.id(),
                    key.keyId(),
                    key.name(),
                    key.role(),
                    key.createdAt(),
                    key.lastUsedAt(),
                    key.revokedAt(),
                    key.rotatedFrom());
        }
    }

    @Schema(
            description =
                    "A newly issued key, with its secret. This is the only time the secret is "
                            + "returned: it is stored encrypted and no endpoint gives it back. "
                            + "Lose it and rotate the key.")
    record IssuedKeyResponse(
            ApiKeyResponse key,
            @Schema(description = "The signing secret", example = "mzs_...") String secret) {
    }
}
