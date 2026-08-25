package dev.kauzes.mizan.common.error;

public class ConflictException extends MizanException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
