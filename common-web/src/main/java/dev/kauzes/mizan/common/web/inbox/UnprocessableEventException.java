package dev.kauzes.mizan.common.web.inbox;

/**
 * Thrown by a handler that will never be able to process this event, however many times it is
 * asked.
 *
 * <p>The distinction this exists to draw is the one that decides whether retrying is useful. A
 * database that is briefly unreachable is worth trying again in a second; a payload from a
 * version this consumer does not understand, or an amount that is not a number, will fail
 * identically forever. Retrying the second kind is not caution, it is a busy loop that keeps
 * every event behind it waiting.
 *
 * <p>Anything thrown that is not this is assumed to be worth retrying, which is the safe
 * default: the cost of retrying something hopeless is a delay, and the cost of not retrying
 * something transient is an event nobody handles.
 */
public class UnprocessableEventException extends RuntimeException {

    public UnprocessableEventException(String message) {
        super(message);
    }

    public UnprocessableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
