package dev.kauzes.mizan.identity.token;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.user.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mints access tokens, and verifies them.
 *
 * <p>A token carries the user, the merchant it acts for and its roles, and is checked by its
 * signature, its issuer and its expiry alone. Nothing is looked up, which is what keeps
 * identity off the path of every payment: if this service is down, tokens it already issued
 * still work.
 *
 * <p>The price of that is that an access token cannot be withdrawn. Its lifetime is the
 * window in which a role change or a dismissal has not taken effect yet, which is why it is
 * minutes rather than hours and why the refresh token, which can be revoked, is the long
 * lived half of the pair.
 */
@Component
public class AccessTokenIssuer {

    /** The same answer for every kind of bad token, so none of them can be told apart. */
    private static final String REFUSED = "The credentials are not valid.";

    private static final String MERCHANT_CLAIM = "merchant";
    private static final String ROLES_CLAIM = "roles";

    private final RSAKey signingKey;
    private final TokenProperties properties;
    private final Clock clock;

    @Autowired
    public AccessTokenIssuer(RSAKey signingKey, TokenProperties properties) {
        this(signingKey, properties, Clock.systemUTC());
    }

    /** For tests that need to mint a token as of some other moment. */
    AccessTokenIssuer(RSAKey signingKey, TokenProperties properties, Clock clock) {
        this.signingKey = signingKey;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UserAccount user) {
        Instant now = clock.instant();
        Instant expiry = now.plus(properties.accessTokenTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(user.id().toString())
                .claim(MERCHANT_CLAIM, user.merchantId().toString())
                .claim(ROLES_CLAIM, user.roles().stream().map(Enum::name).sorted().toList())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(signingKey.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);

        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (Exception unsignable) {
            throw new IllegalStateException("could not sign an access token", unsignable);
        }
        return jwt.serialize();
    }

    /**
     * @throws UnauthorizedException if the token is malformed, signed by another key, issued
     *     by somebody else, expired, or carries a role this platform does not have
     */
    public AccessToken verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(signingKey.toPublicJWK()))) {
                throw new UnauthorizedException(REFUSED);
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())) {
                throw new UnauthorizedException(REFUSED);
            }

            Date expiry = claims.getExpirationTime();
            if (expiry == null || !expiry.toInstant().isAfter(clock.instant())) {
                throw new UnauthorizedException(REFUSED);
            }

            return new AccessToken(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.getStringClaim(MERCHANT_CLAIM)),
                    rolesIn(claims.getStringListClaim(ROLES_CLAIM)),
                    claims.getIssueTime().toInstant(),
                    expiry.toInstant());
        } catch (UnauthorizedException refused) {
            throw refused;
        } catch (Exception unreadable) {
            // A parse failure, a wrong key, a claim of the wrong type: all of it means the
            // same thing to a caller, and saying which would help somebody probing.
            throw new UnauthorizedException(REFUSED);
        }
    }

    private static Set<Role> rolesIn(List<String> names) {
        if (names == null) {
            throw new UnauthorizedException(REFUSED);
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        names.forEach(name -> roles.add(Role.valueOf(name)));
        return roles;
    }
}
