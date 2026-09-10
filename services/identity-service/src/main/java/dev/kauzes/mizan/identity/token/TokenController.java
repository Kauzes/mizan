package dev.kauzes.mizan.identity.token;

import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in, staying signed in, and stopping.
 *
 * <p>Every answer here also sets or clears a cookie holding the refresh token, for the one
 * kind of client that cannot be trusted to store a secret. See {@link SessionCookie} for why
 * that is a cookie and not local storage. The token is still in the body, so nothing that
 * already works stops working.
 */
@RestController
@RequestMapping(path = "/api/v1/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Tokens", description = "Signing in, and staying signed in")
public class TokenController {

    private final TokenService tokens;
    private final SessionCookie sessionCookie;

    public TokenController(TokenService tokens, SessionCookie sessionCookie) {
        this.tokens = tokens;
        this.sessionCookie = sessionCookie;
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
    public ResponseEntity<TokenPair> signIn(@Valid @RequestBody SignInRequest request) {
        return withSession(tokens.signIn(request));
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
    public ResponseEntity<TokenPair> refresh(
            @RequestBody(required = false) RefreshRequest request,
            @CookieValue(name = "${mizan.security.session-cookie.name:mizan_refresh}",
                            required = false)
                    String fromCookie) {

        return withSession(tokens.refresh(presented(request, fromCookie)));
    }

    @PostMapping("/sign-out")
    @PublicEndpoint(
            because = "the refresh token is the credential, the same as refreshing. A caller "
                    + "whose access token has already expired still has to be able to end "
                    + "their session, and that is exactly when they most want to.")
    @NotIdempotent(
            because = "signing out twice is not an error and does not need a stored answer: "
                    + "the second one finds nothing to revoke and says the same thing.")
    @Operation(
            summary = "Sign out",
            description =
                    """
                    Revokes every token descended from this sign in, and clears the cookie.

                    Answers the same way whether the token was real, expired or invented.                     Anything else would be a way to ask whether a token still works, which is                     the question somebody holding a stolen one most wants answered.""")
    @ApiResponse(responseCode = "204", description = "The session is over")
    public ResponseEntity<Void> signOut(
            @RequestBody(required = false) RefreshRequest request,
            @CookieValue(name = "${mizan.security.session-cookie.name:mizan_refresh}",
                            required = false)
                    String fromCookie) {

        tokens.signOut(request != null && request.refreshToken() != null
                ? request.refreshToken()
                : fromCookie);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie.gone().toString())
                .build();
    }

    /**
     * The token being spent: what the caller named, or what their browser is carrying.
     *
     * <p>The body wins. A client that named a token meant that one, and silently spending a
     * different one because a cookie happened to be attached is the kind of surprise that
     * ends with somebody's session revoked for a replay they did not commit.
     */
    private static String presented(RefreshRequest request, String fromCookie) {
        if (request != null && request.refreshToken() != null
                && !request.refreshToken().isBlank()) {
            return request.refreshToken();
        }
        if (fromCookie == null || fromCookie.isBlank()) {
            // Refused the same way a wrong token is, because from the outside they are the
            // same thing: this request presented no credential.
            throw new UnauthorizedException("The credentials are not valid.");
        }
        return fromCookie;
    }

    private ResponseEntity<TokenPair> withSession(TokenPair pair) {
        Duration lifetime = Duration.ofSeconds(pair.refreshExpiresIn());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        sessionCookie.carrying(pair.refreshToken(), lifetime).toString())
                .body(pair);
    }
}
