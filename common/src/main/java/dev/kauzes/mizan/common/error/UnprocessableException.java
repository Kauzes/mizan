package dev.kauzes.mizan.common.error;

public class UnprocessableException extends MizanException {

    public UnprocessableException(String message) {
        super(ErrorCode.UNPROCESSABLE, message);
    }
}
