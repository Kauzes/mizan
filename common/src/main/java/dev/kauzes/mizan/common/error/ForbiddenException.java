package dev.kauzes.mizan.common.error;

public class ForbiddenException extends MizanException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
