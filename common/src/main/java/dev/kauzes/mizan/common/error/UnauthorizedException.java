package dev.kauzes.mizan.common.error;

/**
 * The caller is not who they say they are, or has not said. The message is deliberately the
 * same whatever went wrong: a wrong password, an unknown user and a tampered token all look
 * alike from outside, so none of them can be used to find out which accounts exist.
 */
public class UnauthorizedException extends MizanException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
