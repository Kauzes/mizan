package dev.kauzes.mizan.common.error;

import java.util.List;

public class ValidationFailedException extends MizanException {

    public ValidationFailedException(String message, List<FieldViolation> violations) {
        super(ErrorCode.VALIDATION_FAILED, message, violations, null);
    }
}
