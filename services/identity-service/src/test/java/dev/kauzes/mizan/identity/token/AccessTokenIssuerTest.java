package dev.kauzes.mizan.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.identity.merchant.Merchant;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.user.UserAccount;
import java.lang.reflect.Field;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a token has to withstand. None of this needs a database or a running service, so it
 * runs in the fast suite where a signature check belongs.
 */
class AccessTokenIssuerTest {

    private static final TokenProperties PROPERTIES = new TokenProperties(
            "https://mizan.test/identity", Duration.ofMinutes(15), Duration.ofDays(30), "");

    private final RSAKey key = new SigningKeyConfiguration().signingKey(PROPERTIES);
    private final AccessTokenIssuer issuer = new AccessTokenIssuer(key, PROPERTIES);

    @Test
    void carriesTheUserTheMerchantAndTheRoles() {
        UserAccount user = user();

        AccessToken verified = issuer.verify(issuer.issue(user));

        assertThat(verified.userId()).isEqualTo(user.id());
        assertThat(verified.merchantId()).isEqualTo(user.merchantId());
        assertThat(verified.roles()).containsExactly(Role.OWNER);
        assertThat(verified.expiresAt())
                .as("fifteen minutes, from configuration")
                .isCloseTo(
                        verified.issuedAt().plus(Duration.ofMinutes(15)),
                        org.assertj.core.api.Assertions.within(Duration.ofSeconds(2)));
    }

    @Test
    void refusesATokenThatHasExpired() {
        Clock anHourAgo = Clock.fixed(
                Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String stale = new AccessTokenIssuer(key, PROPERTIES, anHourAgo).issue(user());

        assertRefused(() -> issuer.verify(stale));
    }

    @Test
    void refusesATokenWhoseSignatureWasTamperedWith() {
        String token = issuer.issue(user());
        int lastDot = token.lastIndexOf('.');
        String signature = token.substring(lastDot + 1);
        String flipped = (signature.charAt(0) == 'A' ? 'B' : 'A') + signature.substring(1);

        assertRefused(() -> issuer.verify(token.substring(0, lastDot + 1) + flipped));
    }

    @Test
    void refusesATokenWhosePayloadWasEdited() {
        String token = issuer.issue(user());
        String[] parts = token.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]));
        String elevated = claims.replace("\"OWNER\"", "\"ADMIN\"");

        String forged = parts[0]
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(elevated.getBytes())
                + "."
                + parts[2];

        assertRefused(() -> issuer.verify(forged));
    }

    @Test
    void refusesATokenSignedBySomebodyElse() {
        RSAKey theirKey = new SigningKeyConfiguration().signingKey(PROPERTIES);
        String theirToken = new AccessTokenIssuer(theirKey, PROPERTIES).issue(user());

        assertRefused(() -> issuer.verify(theirToken));
    }

    @Test
    void refusesATokenIssuedBySomebodyElse() {
        TokenProperties elsewhere = new TokenProperties(
                "https://not-mizan.test/", Duration.ofMinutes(15), Duration.ofDays(30), "");
        String foreign = new AccessTokenIssuer(key, elsewhere).issue(user());

        assertRefused(() -> issuer.verify(foreign));
    }

    @Test
    void refusesSomethingThatIsNotATokenAtAll() {
        assertRefused(() -> issuer.verify("not-a-token"));
        assertRefused(() -> issuer.verify(""));
    }

    @Test
    void readsAConfiguredKeyAndKeepsItsIdentity() throws Exception {
        String pem = pemOfAFreshPrivateKey();
        TokenProperties configured = new TokenProperties(
                PROPERTIES.issuer(), Duration.ofMinutes(15), Duration.ofDays(30), pem);

        RSAKey first = new SigningKeyConfiguration().signingKey(configured);
        RSAKey second = new SigningKeyConfiguration().signingKey(configured);

        assertThat(first.getKeyID())
                .as("the same key should have the same id across restarts")
                .isEqualTo(second.getKeyID());
        assertThat(new AccessTokenIssuer(second, configured)
                        .verify(new AccessTokenIssuer(first, configured).issue(user())))
                .as("either instance should verify the other's tokens")
                .isNotNull();
    }

    @Test
    void refusesToStartOnAKeyItCannotRead() {
        TokenProperties broken = new TokenProperties(
                PROPERTIES.issuer(), Duration.ofMinutes(15), Duration.ofDays(30), "not-a-pem");

        assertThatThrownBy(() -> new SigningKeyConfiguration().signingKey(broken))
                .as("starting with a key nobody can verify against is worse than not starting")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mizan.security.jwt.private-key");
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(UnauthorizedException.class)
                .satisfies(refusal -> assertThat(((MizanException) refusal).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED))
                .as("every refusal says the same thing, whatever was wrong")
                .hasMessage("The credentials are not valid.");
    }

    private static String pemOfAFreshPrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    /** A user that was never saved, since nothing here reads one back. */
    private static UserAccount user() {
        Merchant merchant = new Merchant("Kauzes Coffee");
        assign(merchant, "id", UUID.randomUUID());

        UserAccount user = new UserAccount(
                merchant, "owner@kauzes.dev", "irrelevant", "Sam Kauzes", Role.OWNER);
        assign(user, "id", UUID.randomUUID());
        return user;
    }

    /** Ids are the database's to give, and this test has no database. */
    private static void assign(Object target, String field, Object value) {
        try {
            Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
