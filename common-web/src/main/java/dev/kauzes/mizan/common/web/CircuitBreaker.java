package dev.kauzes.mizan.common.web;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops asking something that is not answering.
 *
 * <p>The first thing on this platform that gives up early on purpose. Every other outbound call
 * retries or resolves, because everywhere else the answer matters enough to wait for. Here it
 * does not: a risk check is a guard in front of an authorization, and a guard that costs one
 * timeout per payment while it is down has stopped protecting anything and started being the
 * outage.
 *
 * <p>Without a breaker, risk being unavailable costs every payment its full timeout — so a
 * platform taking ten payments a second accumulates ten seconds of latency every second, which
 * is how one slow dependency becomes a queue nobody drains. With one, the first few failures
 * cost a timeout each and everything after is refused instantly until it is worth trying again.
 *
 * <h2>Three states</h2>
 *
 * <ul>
 *   <li><b>Closed</b> — calls go through. Failures are counted.
 *   <li><b>Open</b> — calls are refused immediately, without trying. After a wait, one call is
 *       let through to find out whether it is worth reopening.
 *   <li><b>Half open</b> — that one call is in flight. If it works the breaker closes; if it
 *       fails it opens again for another wait.
 * </ul>
 *
 * <p>Hand written rather than a library, deliberately. It is sixty lines and this platform has
 * been caught out repeatedly by Boot 4's module splits; a dependency whose Spring integration
 * may or may not exist yet, for behaviour this size, is the more expensive of the two.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** What a breaker refuses with, so a caller can tell it apart from a real failure. */
    public static class CircuitOpenException extends RuntimeException {

        public CircuitOpenException(String name) {
            super(name + " is not answering, so it was not asked");
        }
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String name;
    private final int failuresBeforeOpening;
    private final Duration stayOpenFor;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public CircuitBreaker(String name, int failuresBeforeOpening, Duration stayOpenFor) {
        this.name = name;
        this.failuresBeforeOpening = failuresBeforeOpening;
        this.stayOpenFor = stayOpenFor;
    }

    /**
     * Runs the call, unless the breaker is open.
     *
     * @throws CircuitOpenException without calling anything, when it is open
     */
    public <T> T call(Supplier<T> work) {
        if (isOpen()) {
            throw new CircuitOpenException(name);
        }

        try {
            T result = work.get();
            succeeded();
            return result;
        } catch (RuntimeException failed) {
            failed();
            throw failed;
        }
    }

    /**
     * Whether to refuse without trying.
     *
     * <p>Reads the clock rather than being driven by a timer, so a breaker that opened and was
     * then never asked again does not hold a thread to close itself.
     */
    private boolean isOpen() {
        Instant since = openedAt.get();
        if (since == null) {
            return false;
        }
        if (Instant.now().isBefore(since.plus(stayOpenFor))) {
            return true;
        }
        // Time to find out. Clearing the mark lets exactly one call through — the next failure
        // sets it again, and the next success closes properly.
        if (openedAt.compareAndSet(since, null)) {
            log.info("{} has been left alone long enough; trying one call", name);
        }
        return false;
    }

    private void succeeded() {
        if (consecutiveFailures.getAndSet(0) >= failuresBeforeOpening) {
            log.info("{} is answering again", name);
        }
        openedAt.set(null);
    }

    private void failed() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failuresBeforeOpening && openedAt.compareAndSet(null, Instant.now())) {
            log.warn(
                    "{} has failed {} times in a row, so it will not be asked for {}",
                    name,
                    failures,
                    stayOpenFor);
        }
    }

    /** What state it is in, for anything that wants to show it. */
    public State state() {
        Instant since = openedAt.get();
        if (since == null) {
            return consecutiveFailures.get() >= failuresBeforeOpening
                    ? State.HALF_OPEN
                    : State.CLOSED;
        }
        return State.OPEN;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public String name() {
        return name;
    }
}
