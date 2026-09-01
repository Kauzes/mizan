package dev.kauzes.mizan.gateway.auth;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Turns a bearer token into a caller, or refuses it.
 *
 * <p>Signature, issuer and expiry, and nothing else: no call to identity, no database. A
 * token that passes here is trusted for exactly as long as it says it should be.
 */
@Component
public class AccessTokenVerifier {

    /** One answer for every bad token, so none of them can be told apart by probing. */
    private static final String REFUSED = "The credentials are not valid.";

    private final SigningKeys keys;
    private final AuthenticationProperties properties;
    private final Clock clock;

    @Autowired
    public AccessTokenVerifier(SigningKeys keys, AuthenticationProperties properties) {
        this(keys, properties, Clock.systemUTC());
    }

    AccessTokenVerifier(SigningKeys keys, AuthenticationProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<VerifiedCaller> verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (java.text.ParseException unreadable) {
            return Mono.error(new UnauthorizedException(REFUSED));
        }

        String keyId = jwt.getHeader().getKeyID();
        if (keyId == null) {
            return Mono.error(new UnauthorizedException(REFUSED));
        }

        return keys.withId(keyId)
                // A key set that cannot be fetched is the platform's problem, not the
                // caller's, and saying 401 would send them off to fix credentials that are
                // perfectly good.
                .onErrorMap(
                        failure -> !(failure instanceof MizanException),
                        failure -> new MizanException(
                                ErrorCode.UPSTREAM_UNAVAILABLE,
                                "Authentication is temporarily unavailable.",
                                failure))
                .flatMap(key -> key.map(found -> established(jwt, found))
                        .orElseGet(() -> Mono.error(new UnauthorizedException(REFUSED))));
    }

    private Mono<VerifiedCaller> established(SignedJWT jwt, JWK key) {
        try {
            if (!jwt.verify(new RSASSAVerifier(key.toRSAKey()))) {
                return Mono.error(new UnauthorizedException(REFUSED));
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())) {
                return Mono.error(new UnauthorizedException(REFUSED));
            }

            Date expiry = claims.getExpirationTime();
            if (expiry == null || !expiry.toInstant().isAfter(clock.instant())) {
                return Mono.error(new UnauthorizedException(REFUSED));
            }

            String merchant = claims.getStringClaim(CallerIdentity.MERCHANT_CLAIM);
            List<String> roles = claims.getStringListClaim(CallerIdentity.ROLES_CLAIM);
            if (claims.getSubject() == null || merchant == null || roles == null) {
                return Mono.error(new UnauthorizedException(REFUSED));
            }

            return Mono.just(VerifiedCaller.user(claims.getSubject(), merchant, roles));
        } catch (Exception unusable) {
            return Mono.error(new UnauthorizedException(REFUSED));
        }
    }
}
