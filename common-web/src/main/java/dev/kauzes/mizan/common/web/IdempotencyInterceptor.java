package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.ValidationFailedException;
import dev.kauzes.mizan.common.error.FieldViolation;
import dev.kauzes.mizan.common.identity.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Decides whether this request has been made before, and answers it with what happened last
 * time if it has.
 *
 * <p>The order is what makes this work. The key is claimed before the handler runs and
 * committed on its own, so a second request arriving at the same instant finds the claim
 * rather than a free key. The winner does the work; everybody else reads what it did.
 *
 * <p>What is stored is the response the handler produced, so a repeat is answered with the
 * same status and the same body. A caller retrying after a lost response cannot tell its
 * retry from the call it is retrying, which is the point: if it could, it would have to
 * handle a second shape of success on the path it reaches only when something went wrong.
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    static final String HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    private static final String CLAIMED = IdempotencyInterceptor.class.getName() + ".claimed";
    private static final int MAXIMUM_KEY_LENGTH = 200;

    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!(handler instanceof HandlerMethod method)
                || !method.hasMethodAnnotation(Idempotent.class)) {
            return true;
        }

        String key = keyOn(request);
        Caller caller = CallerHeaders.of(request);
        String endpoint = endpointOf(request);
        String fingerprint = fingerprintOf(request);

        Optional<IdempotencyStore.Recorded> existing =
                store.claim(caller.merchantId(), endpoint, key, fingerprint);

        if (existing.isEmpty()) {
            request.setAttribute(CLAIMED, new Claim(caller.merchantId(), endpoint, key));
            return true;
        }

        IdempotencyStore.Recorded recorded = existing.get();
        if (!recorded.fingerprint().equals(fingerprint)) {
            // A key reused for a different request is somebody's bug. Answering it with the
            // earlier result would hide the bug behind a success.
            throw new ConflictException(
                    "Idempotency-Key " + key + " was already used for a different request.");
        }

        if (!recorded.complete()) {
            // The first attempt is still in flight. Saying so is better than waiting for it
            // and better than starting a second one.
            throw new MizanException(
                    ErrorCode.CONTENDED,
                    "A request with this Idempotency-Key is already in progress. Try again in "
                            + "a moment; it is safe to, because the key is the same.");
        }

        log.info("replayed {} for key {}", endpoint, key);
        writeBack(response, recorded);
        return false;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception failure) {

        if (!(request.getAttribute(CLAIMED) instanceof Claim claim)) {
            return;
        }

        boolean succeeded = failure == null && response.getStatus() >= 200
                && response.getStatus() < 300;

        if (!succeeded) {
            // A failure is not an outcome worth replaying. A caller retrying after a 500
            // wants another attempt rather than the 500 again, and one retrying after a 400
            // will be told the same thing by the handler anyway.
            store.release(claim.merchantId(), claim.endpoint(), claim.key());
            return;
        }

        store.complete(
                claim.merchantId(),
                claim.endpoint(),
                claim.key(),
                response.getStatus(),
                bodyOf(response));
    }

    private static String keyOn(HttpServletRequest request) {
        String key = request.getHeader(HEADER);

        if (key == null || key.isBlank()) {
            throw new ValidationFailedException(
                    "This endpoint needs an Idempotency-Key, so that repeating the request "
                            + "cannot repeat its effect.",
                    List.of(new FieldViolation(HEADER, "must be sent")));
        }
        if (key.length() > MAXIMUM_KEY_LENGTH) {
            throw new ValidationFailedException(
                    "That Idempotency-Key is too long.",
                    List.of(new FieldViolation(
                            HEADER, "must be at most " + MAXIMUM_KEY_LENGTH + " characters")));
        }
        return key.trim();
    }

    /**
     * The mapped pattern rather than the path that matched it, so one key means one thing per
     * operation rather than per resource it happened to name.
     */
    private static String endpointOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + " " + (pattern == null ? request.getRequestURI() : pattern);
    }

    /** A digest of what was asked for, so a repeat can be told from a collision. */
    private static String fingerprintOf(HttpServletRequest request) {
        Object body = request.getAttribute(IdempotencyFilter.BUFFERED_BODY);
        byte[] bytes = body instanceof byte[] read ? read : new byte[0];

        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((request.getRequestURI() + "\n").getBytes(StandardCharsets.UTF_8)))
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
        }
    }

    private static void writeBack(HttpServletResponse response, IdempotencyStore.Recorded recorded)
            throws Exception {

        response.setStatus(recorded.status() == null ? 200 : recorded.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (recorded.body() != null) {
            response.getWriter().write(recorded.body());
        }
    }

    private static String bodyOf(HttpServletResponse response) {
        if (response instanceof org.springframework.web.util.ContentCachingResponseWrapper
                captured) {
            return new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private record Claim(UUID merchantId, String endpoint, String key) {
    }
}
