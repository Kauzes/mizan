package dev.kauzes.mizan.common.error;

/**
 * The stable machine readable part of an error response. Callers branch on these, so a
 * code is never renamed or reused for a different meaning once it has shipped.
 */
public enum ErrorCode {

    VALIDATION_FAILED("validation-failed", 400),
    MALFORMED_REQUEST("malformed-request", 400),
    UNAUTHORIZED("unauthorized", 401),
    FORBIDDEN("forbidden", 403),
    NOT_FOUND("not-found", 404),
    METHOD_NOT_ALLOWED("method-not-allowed", 405),
    CONFLICT("conflict", 409),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", 409),
    UNPROCESSABLE("unprocessable", 422),
    RATE_LIMITED("rate-limited", 429),
    INTERNAL_ERROR("internal-error", 500),
    UPSTREAM_UNAVAILABLE("upstream-unavailable", 503),
    UPSTREAM_TIMEOUT("upstream-timeout", 504);

    private static final String TYPE_PREFIX = "https://mizan.kauzes.dev/errors/";

    private final String slug;
    private final int status;

    ErrorCode(String slug, int status) {
        this.slug = slug;
        this.status = status;
    }

    public String slug() {
        return slug;
    }

    public int status() {
        return status;
    }

    /** The RFC 9457 `type` URI. It identifies the error, and is not required to resolve. */
    public String type() {
        return TYPE_PREFIX + slug;
    }
}
