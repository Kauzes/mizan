package dev.kauzes.mizan.identity.apikey;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.Idempotent;
import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.RequiresPermission;
import dev.kauzes.mizan.identity.apikey.ApiKeyResponses.ApiKeyResponse;
import dev.kauzes.mizan.identity.apikey.ApiKeyResponses.IssueKeyRequest;
import dev.kauzes.mizan.identity.apikey.ApiKeyResponses.IssuedKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The keys a merchant's own servers authenticate with.
 *
 * <p>Managed by a person signed in to the console, which is why these endpoints are guarded
 * by a permission like any other. A key cannot manage keys unless its role says so, and the
 * only role that does is the owner's.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/api-keys",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "API keys", description = "Credentials a merchant's server signs requests with")
public class ApiKeyController {

    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping
    @RequiresPermission(Permission.API_KEY_MANAGE)
    @Operation(
            summary = "List a merchant's API keys",
            description = "Secrets are not included, and cannot be retrieved after issue.")
    @ApiResponse(responseCode = "200", description = "The merchant's keys, oldest first")
    public List<ApiKeyResponse> list(@PathVariable UUID merchantId) {
        return keys.list(merchantId);
    }

    @PostMapping
    @RequiresPermission(Permission.API_KEY_MANAGE)
    @Idempotent
    @Operation(
            summary = "Issue an API key",
            description =
                    "The response carries the secret. It is the only time it is returned: the "
                            + "stored copy is encrypted and no endpoint gives it back.")
    @ApiResponse(responseCode = "201", description = "The key, and its secret, once")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    public ResponseEntity<IssuedKeyResponse> issue(
            @PathVariable UUID merchantId, @Valid @RequestBody IssueKeyRequest request) {

        IssuedKeyResponse issued = keys.issue(merchantId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/api-keys/"
                                + issued.key().id()))
                .body(issued);
    }

    @PostMapping("/{keyId}/rotate")
    @RequiresPermission(Permission.API_KEY_MANAGE)
    @Idempotent
    @Operation(
            summary = "Rotate an API key",
            description =
                    "Issues a replacement carrying the same name and role, and revokes this "
                            + "one. The old key stops working at once, so deploy the new secret "
                            + "before rotating.")
    @ApiResponse(responseCode = "201", description = "The replacement key, and its secret, once")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no key with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "That key is already revoked")
    public ResponseEntity<IssuedKeyResponse> rotate(
            @PathVariable UUID merchantId, @PathVariable UUID keyId) {

        IssuedKeyResponse issued = keys.rotate(merchantId, keyId);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/api-keys/"
                                + issued.key().id()))
                .body(issued);
    }

    @DeleteMapping("/{keyId}")
    @RequiresPermission(Permission.API_KEY_MANAGE)
    @NotIdempotent(
            because = "revoking a key that is already revoked leaves it revoked")
    @Operation(
            summary = "Revoke an API key",
            description = "Takes effect on the next request, not on the next cache expiry.")
    @ApiResponse(responseCode = "200", description = "The key, now revoked")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no key with that id")
    public ApiKeyResponse revoke(@PathVariable UUID merchantId, @PathVariable UUID keyId) {
        return keys.revoke(merchantId, keyId);
    }
}
