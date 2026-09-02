package dev.kauzes.mizan.common.web.outbox;

/**
 * One kind of event a service publishes.
 *
 * <p>Implemented by an enum in each producing service, so that the events a service can emit
 * are a list somebody wrote down rather than a set of strings discovered by reading every
 * call site. Adding one is then a visible decision, which is the point: a published event is
 * a promise to whoever consumes it, and promises should not appear by accident.
 *
 * <p>Each service keeps its own catalogue rather than a platform-wide one. A shared enum
 * would mean every service recompiles when any service publishes something new, and would put
 * the definition of a payment event somewhere other than the payment service.
 */
public interface EventType {

    /**
     * The name on the wire, as {@code <aggregate>.<what happened>}, past tense.
     *
     * <p>Past tense because an event is a record of something that has already happened, not
     * a request for something to happen. A consumer that receives {@code payment.capture} will
     * eventually be written as though it were being asked to capture something.
     */
    String type();

    /**
     * The shape of the payload, starting at 1.
     *
     * <p>Incremented when the payload changes in a way a consumer could not survive. A field
     * added is not that; a field removed, renamed, or given a new meaning is.
     */
    int version();

    /** What kind of thing these events happen to. */
    String aggregateType();
}
