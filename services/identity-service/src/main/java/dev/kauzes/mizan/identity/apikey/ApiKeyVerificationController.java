package dev.kauzes.mizan.identity.apikey;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the gateway asks whether a signature holds up.
 *
 * <p>Deliberately not under {@code /api/}: this is not part of the API a merchant calls, it
 * is one component of the platform asking another, and the gateway does not publish a route
 * to it. It is hidden from the specification for the same reason.
 *
 * <p>Asking on every signed request, rather than handing the gateway the secrets and letting
 * it cache them, is what makes a revocation take effect immediately. It also keeps every
 * secret inside the one service that is allowed to hold them.
 */
@Hidden
@RestController
public class ApiKeyVerificationController {

    private final ApiKeyService keys;

    public ApiKeyVerificationController(ApiKeyService keys) {
        this.keys = keys;
    }

    @PostMapping(path = "/internal/api-keys/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public SignedRequest.Verified verify(@Valid @RequestBody SignedRequest.Verification presented) {
        return keys.verify(presented);
    }
}
