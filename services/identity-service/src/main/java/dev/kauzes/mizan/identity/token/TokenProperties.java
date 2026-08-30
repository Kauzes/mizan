package dev.kauzes.mizan.identity.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a token lives, who issued it, and what signs it.
 *
 * <p>Lifetimes are configuration rather than constants because the right access token
 * lifetime is a trade between how quickly a revoked role stops mattering and how often a
 * client has to refresh, and that answer differs between a demo and a deployment.
 *
 * @param issuer the {@code iss} claim, and what a verifier checks it against
 * @param accessTokenTtl minutes, not hours: nothing revokes an access token, so its lifetime
 *     is the window in which a change of roles has not taken effect yet
 * @param refreshTokenTtl days: how long a client can stay signed in without a password
 * @param privateKey a PKCS#8 PEM private key. Empty means one is generated at startup, which
 *     is fine locally and wrong anywhere else; the service says so loudly when it happens
 */
@ConfigurationProperties(prefix = "mizan.security.jwt")
public record TokenProperties(
        String issuer, Duration accessTokenTtl, Duration refreshTokenTtl, String privateKey) {

    public TokenProperties {
        issuer = issuer == null || issuer.isBlank() ? "https://mizan.local/identity" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
        privateKey = privateKey == null ? "" : privateKey.trim();
    }

    public boolean hasConfiguredKey() {
        return !privateKey.isEmpty();
    }
}
