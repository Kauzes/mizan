package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.FieldViolation;
import dev.kauzes.mizan.common.error.MizanException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the platform as an RFC 9457 problem detail carrying a stable code and
 * the correlation id. Anything not raised deliberately is reported as an internal error
 * with no detail, so a stack trace or an internal class name never reaches a caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MizanException.class)
    public ResponseEntity<ProblemDetail> handleMizan(MizanException exception) {
        ErrorCode code = exception.errorCode();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(code.status()), exception.getMessage());
        decorate(body, code, exception.violations());

        if (code.status() >= 500) {
            log.error("{} failed the request", code.slug(), exception);
        } else {
            log.warn("{}: {}", code.slug(), exception.getMessage());
        }
        return ResponseEntity.status(code.status()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("unhandled exception", exception);

        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
        decorate(body, ErrorCode.INTERNAL_ERROR, List.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(
                        error.getField(),
                        error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request failed validation.");
        decorate(body, ErrorCode.VALIDATION_FAILED, violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(exception, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            decorate(problem, codeFor(status), List.of());
        }
        return response;
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.CONFLICT;
            case 422 -> ErrorCode.UNPROCESSABLE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.UPSTREAM_UNAVAILABLE;
            case 504 -> ErrorCode.UPSTREAM_TIMEOUT;
            default -> status.is5xxServerError()
                    ? ErrorCode.INTERNAL_ERROR
                    : ErrorCode.MALFORMED_REQUEST;
        };
    }

    private static void decorate(
            ProblemDetail body, ErrorCode code, List<FieldViolation> violations) {

        body.setType(URI.create(code.type()));
        body.setTitle(code.slug());
        body.setProperty("code", code.name());
        body.setProperty("correlationId", CorrelationContext.currentOrEmpty());
        body.setProperty("timestamp", Instant.now().toString());
        if (!violations.isEmpty()) {
            body.setProperty("errors", violations);
        }
    }
}
