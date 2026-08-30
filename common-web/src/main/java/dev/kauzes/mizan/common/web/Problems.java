package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.FieldViolation;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the one error body the platform returns.
 *
 * <p>This exists so that the servlet handler and the gateway, which cannot share a handler
 * because one is reactive, cannot disagree about the shape either. A caller parsing a 401
 * from the gateway and a 409 from a service is parsing the same thing.
 */
public final class Problems {

    private Problems() {
    }

    /** For a servlet request, where the correlation id is already in the logging context. */
    public static ProblemDetail of(ErrorCode code, String detail, List<FieldViolation> violations) {
        return of(code, detail, CorrelationContext.currentOrEmpty(), violations);
    }

    /**
     * For anywhere the correlation id has to be passed in, such as a reactive filter that
     * reads it off the request rather than out of a thread local.
     */
    public static ProblemDetail of(
            ErrorCode code, String detail, String correlationId, List<FieldViolation> violations) {

        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        decorate(body, code, correlationId, violations);
        return body;
    }

    /** Adds the platform's fields to a problem detail Spring built for us. */
    public static void decorate(
            ProblemDetail body,
            ErrorCode code,
            String correlationId,
            List<FieldViolation> violations) {

        body.setType(URI.create(code.type()));
        body.setTitle(code.slug());
        body.setProperty("code", code.name());
        body.setProperty("correlationId", correlationId);
        body.setProperty("timestamp", Instant.now().toString());
        if (!violations.isEmpty()) {
            body.setProperty("errors", violations);
        }
    }
}
