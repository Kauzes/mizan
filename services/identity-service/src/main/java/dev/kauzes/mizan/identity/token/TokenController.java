package dev.kauzes.mizan.identity.token;

import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Tokens", description = "Signing in, and staying signed in")
public class TokenController {

    private final TokenService tokens;

    public TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping
    @PublicEndpoint(because = "nobody has a token before they sign in")
    @NotIdempotent(because = "there is no merchant yet to scope a key to")
    @Operation(
            summary = "Sign in",
            description = "Exchanges an email and a password for an access and refresh token.")
    @ApiResponse(responseCode = "200", description = "A new token pair")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "401",
            ref = "#/components/responses/UNAUTHORIZED",
            description =
                    "The credentials are not valid. A wrong password and an address with no "
                            + "account are answered identically.")
    public TokenPair signIn(@Valid @RequestBody SignInRequest request) {
        return tokens.signIn(request);
    }

    @PostMapping("/refresh")
    @PublicEndpoint(because = "the refresh token is the credential; no access token is held")
    @NotIdempotent(
            because = "the refresh token is single use, which is a stronger guarantee "
                    + "than a key: presenting it twice is caught and revokes the family")
    @Operation(
            summary = "Refresh a token pair",
            description =
                    "Spends the refresh token presented and issues a new pair. Presenting one "
                            + "that was already spent revokes every token descended from that "
                            + "sign in, so the session has to be started again.")
    @ApiResponse(responseCode = "200", description = "A new token pair")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(responseCode = "401", ref = "#/components/responses/UNAUTHORIZED")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return tokens.refresh(request.refreshToken());
    }
}
