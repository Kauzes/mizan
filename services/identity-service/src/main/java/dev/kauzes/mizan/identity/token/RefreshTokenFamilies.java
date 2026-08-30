package dev.kauzes.mizan.identity.token;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoking a family of refresh tokens, in a transaction of its own.
 *
 * <p>This exists because of an ordering problem that is easy to get wrong. A replay is
 * discovered inside the refresh transaction, and the way a caller is refused is by throwing,
 * which rolls that transaction back. A revocation written there would be rolled back with it
 * and the stolen token would go on working — the refusal would look right and change
 * nothing. Committing it separately is what makes the revocation stick.
 */
@Component
class RefreshTokenFamilies {

    private final RefreshTokenRepository refreshTokens;

    RefreshTokenFamilies(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int revoke(UUID familyId, Instant moment) {
        return refreshTokens.revokeFamily(familyId, moment);
    }
}
