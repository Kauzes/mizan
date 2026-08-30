package dev.kauzes.mizan.gateway.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the gateway needs in order to check a token: who is allowed to have issued it, and
 * where to find the public key it was signed with.
 *
 * @param issuer the only {@code iss} this gateway accepts
 * @param jwkSetUri where identity publishes the public half of its signing key
 * @param keyCacheTtl how long a fetched key set is used before being fetched again
 * @param minimumRefreshInterval a floor on refetching after an unknown key id, so a stream of
 *     tokens signed by a key nobody has cannot turn into a stream of requests to identity
 */
@ConfigurationProperties(prefix = "mizan.security.jwt")
public record AuthenticationProperties(
        String issuer,
        String jwkSetUri,
        Duration keyCacheTtl,
        Duration minimumRefreshInterval) {

    public AuthenticationProperties {
        issuer = issuer == null || issuer.isBlank() ? "https://mizan.local/identity" : issuer;
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank()
                ? "http://localhost:8081/.well-known/jwks.json"
                : jwkSetUri;
        keyCacheTtl = keyCacheTtl == null ? Duration.ofMinutes(10) : keyCacheTtl;
        minimumRefreshInterval =
                minimumRefreshInterval == null ? Duration.ofSeconds(30) : minimumRefreshInterval;
    }
}
