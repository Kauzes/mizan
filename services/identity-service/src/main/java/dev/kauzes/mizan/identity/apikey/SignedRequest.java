package dev.kauzes.mizan.identity.apikey;

import dev.kauzes.mizan.common.identity.Role;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * What the gateway saw, and what identity says about it.
 *
 * <p>The gateway sends the parts of the canonical request rather than the request itself: it
 * has already hashed the body, and the signature covers the parts, so identity has everything
 * it needs to recompute without the body ever leaving the edge.
 */
final class SignedRequest {

    private SignedRequest() {
    }

    record Verification(
            @NotBlank String keyId,
            @NotBlank String signature,
            @NotBlank String method,
            @NotBlank String path,
            long timestamp,
            @NotBlank String bodyHash) {
    }

    /**
     * Who the key says is calling, once the signature has held up.
     *
     * <p>The principal is the key's own id rather than the public {@code keyId}, so that a
     * service downstream reads one shape of identity whether the caller was a person or a
     * server: an id, a merchant, and what they may do.
     */
    record Verified(UUID principalId, UUID merchantId, String keyId, Role role) {
    }
}
