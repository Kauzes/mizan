package dev.kauzes.mizan.identity.token;

import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.identity.user.UserAccount;
import dev.kauzes.mizan.identity.user.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing in, and staying signed in.
 *
 * <p>Every refusal here is the same refusal. A wrong password, an address nobody has
 * registered, an expired token and one that was tampered with all return the same code and
 * the same message, because anything that told them apart would answer the question "does
 * this person have an account with you" for whoever asked.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final String REFUSED = "The credentials are not valid.";

    /** 256 bits, which is enough that guessing one is not a threat worth modelling. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenFamilies families;
    private final AccessTokenIssuer accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenProperties properties;
    private final Clock clock;

    /**
     * A hash of nothing anybody knows, compared against when the address is unknown so that
     * an unregistered address does not answer faster than a wrong password would.
     */
    private final String decoyHash;

    @Autowired
    public TokenService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            RefreshTokenFamilies families,
            AccessTokenIssuer accessTokens,
            PasswordEncoder passwordEncoder,
            TokenProperties properties) {
        this(
                users,
                refreshTokens,
                families,
                accessTokens,
                passwordEncoder,
                properties,
                Clock.systemUTC());
    }

    TokenService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            RefreshTokenFamilies families,
            AccessTokenIssuer accessTokens,
            PasswordEncoder passwordEncoder,
            TokenProperties properties,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.families = families;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode(randomSecret());
    }

    @Transactional
    public TokenPair signIn(SignInRequest request) {
        Optional<UserAccount> found = users.findByEmail(UserAccount.normalise(request.email()));

        if (found.isEmpty()) {
            // Deliberately does the work anyway. Returning early here would make an unknown
            // address measurably quicker to refuse than a known one with a wrong password.
            passwordEncoder.matches(request.password(), decoyHash);
            throw new UnauthorizedException(REFUSED);
        }

        UserAccount user = found.get();
        if (!user.passwordMatches(request.password(), passwordEncoder)) {
            throw new UnauthorizedException(REFUSED);
        }

        log.info("signed in user {}", user.id());
        return issue(user, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for a new pair, and spends the one presented.
     *
     * <p>A token that was already spent means the same token reached this endpoint twice.
     * That is either a client replaying its own token or somebody using a stolen copy, and
     * from here the two are indistinguishable, so the family is revoked and both the thief
     * and the rightful holder have to sign in again. Losing a session beats keeping one that
     * somebody else is also holding.
     */
    @Transactional
    public TokenPair refresh(String presented) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens
                .findByTokenHash(digestOf(presented))
                .orElseThrow(() -> new UnauthorizedException(REFUSED));

        if (token.isSpent()) {
            // Committed on its own, because this transaction is about to roll back.
            int revoked = families.revoke(token.familyId(), now);
            log.warn(
                    "a spent refresh token was presented again, so family {} was revoked "
                            + "({} tokens)",
                    token.familyId(),
                    revoked);
            throw new UnauthorizedException(REFUSED);
        }

        if (token.isRevoked() || token.hasExpiredBy(now)) {
            throw new UnauthorizedException(REFUSED);
        }

        token.spend(now);
        return issue(token.user(), token.familyId());
    }

    private TokenPair issue(UserAccount user, UUID familyId) {
        Instant now = clock.instant();
        String refreshToken = randomSecret();

        refreshTokens.save(new RefreshToken(
                familyId,
                user,
                digestOf(refreshToken),
                now,
                now.plus(properties.refreshTokenTtl())));

        return TokenPair.bearer(
                accessTokens.issue(user),
                properties.accessTokenTtl().toSeconds(),
                refreshToken,
                properties.refreshTokenTtl().toSeconds());
    }

    private static String randomSecret() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** What is stored, so that a copy of the table is not a set of working tokens. */
    private static String digestOf(String token) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
        }
    }
}
