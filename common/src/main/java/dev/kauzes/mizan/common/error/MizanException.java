package dev.kauzes.mizan.common.error;

import java.util.List;
import java.util.Objects;

/**
 * Base for errors the platform reports deliberately. Anything not extending this is
 * treated as a bug and reported as an internal error with no detail.
 */
public class MizanException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<FieldViolation> violations;

    public MizanException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public MizanException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, List.of(), cause);
    }

    public MizanException(
            ErrorCode errorCode, String message, List<FieldViolation> violations, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
