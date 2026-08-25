package dev.kauzes.mizan.common.error;

public class NotFoundException extends MizanException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
