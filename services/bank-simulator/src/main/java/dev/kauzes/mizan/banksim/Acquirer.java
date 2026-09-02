package dev.kauzes.mizan.banksim;

import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.banksim.AcquirerRequests.AuthorizationResponse;
import dev.kauzes.mizan.banksim.AcquirerRequests.AuthorizeRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A bank, for the purposes of not needing one.
 *
 * <p>Kept in memory on purpose. This stands in for a system outside the platform, and giving
 * it a database would suggest the platform owns what it knows. A restart forgets every
 * authorization, which is the right amount of durability for something whose whole job is to
 * be predictable while the real thing is not.
 */
@Service
public class Acquirer {

    private static final Logger log = LoggerFactory.getLogger(Acquirer.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, Authorization> byRequest = new ConcurrentHashMap<>();
    private final Map<String, Authorization> byReference = new ConcurrentHashMap<>();

    private final Duration slowness;

    public Acquirer(@Value("${mizan.acquirer.slow-response:30s}") Duration slowness) {
        this.slowness = slowness;
    }

    /**
     * Decides an authorization, or hands back the decision already made for this request.
     *
     * <p>A real acquirer does the same, and it matters here for the same reason: a caller
     * that did not hear the answer will ask again, and asking again must not reserve the
     * money a second time.
     */
    public AuthorizationResponse authorize(AuthorizeRequest request) {
        Authorization existing = byRequest.get(request.requestId());
        if (existing != null) {
            log.info("returning the earlier decision for {}", request.requestId());
            return responseFor(existing);
        }

        Behaviour behaviour = Behaviour.of(request.card());

        // Recorded before the answer is withheld, never after. That ordering is the whole
        // point of the slow card: the money is reserved, the caller does not know it, and a
        // lookup during the wait already finds it. MIZ-44 resolves exactly that state.
        Authorization decided =
                byRequest.computeIfAbsent(request.requestId(), id -> record(request, behaviour));

        if (behaviour.isSlow()) {
            waitLongerThanTheCallerWill();
        }

        log.info(
                "{} {} for {}",
                decided.outcome(),
                decided.acquirerReference(),
                request.requestId());
        return responseFor(decided);
    }

    public AuthorizationResponse capture(String acquirerReference) {
        Authorization authorization = find(acquirerReference);
        try {
            authorization.capture();
        } catch (IllegalStateException refused) {
            throw new UnprocessableException(refused.getMessage());
        }
        return responseFor(authorization);
    }

    public AuthorizationResponse voidAuthorization(String acquirerReference) {
        Authorization authorization = find(acquirerReference);
        try {
            authorization.voidIt();
        } catch (IllegalStateException refused) {
            throw new UnprocessableException(refused.getMessage());
        }
        return responseFor(authorization);
    }

    /**
     * What happened to a request, asked by the identifier the caller chose.
     *
     * <p>This is the endpoint that makes a lost answer recoverable, and the reason this
     * simulator keeps anything at all.
     */
    public Optional<AuthorizationResponse> lookUp(String requestId) {
        return Optional.ofNullable(byRequest.get(requestId)).map(Acquirer::responseFor);
    }

    private Authorization record(AuthorizeRequest request, Behaviour behaviour) {
        Authorization authorization = new Authorization(
                "auth_" + reference(),
                request.requestId(),
                behaviour.declines()
                        ? AuthorizationOutcome.DECLINED
                        : AuthorizationOutcome.APPROVED,
                behaviour.reason(),
                request.amount(),
                request.currency(),
                request.card().substring(request.card().length() - 4));

        byReference.put(authorization.acquirerReference(), authorization);
        return authorization;
    }

    private Authorization find(String acquirerReference) {
        Authorization authorization = byReference.get(acquirerReference);
        if (authorization == null) {
            throw new NotFoundException("No authorization with that reference.");
        }
        return authorization;
    }

    private void waitLongerThanTheCallerWill() {
        try {
            Thread.sleep(slowness.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static AuthorizationResponse responseFor(Authorization authorization) {
        return new AuthorizationResponse(
                authorization.acquirerReference(),
                authorization.requestId(),
                authorization.outcome(),
                authorization.reason(),
                authorization.amount(),
                authorization.currency(),
                authorization.cardLastFour(),
                authorization.decidedAt(),
                authorization.state());
    }

    private static String reference() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
