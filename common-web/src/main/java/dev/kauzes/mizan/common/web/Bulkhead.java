package dev.kauzes.mizan.common.web;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A limit on how many calls to one dependency may be waiting on it at once.
 *
 * <p>A breaker notices a dependency that fails. It does not notice one that is merely slow and
 * still answering, and a slow dependency is the more dangerous of the two: every call to it holds
 * a request thread and, on this platform, a database connection, for as long as it waits. With
 * nothing in the way, a slow acquirer takes every connection the pool has, and then a merchant
 * reading a payment waits too, for a connection, behind calls that have nothing to do with them.
 *
 * <p>So a few calls may wait on it at once, and the rest are refused. A caller that is refused was
 * never sent, which is a different and safer fact than a call that was sent and not answered.
 *
 * <p>A turn is waited for briefly rather than not at all. An ordinary burst of calls to a healthy
 * dependency overlaps for milliseconds, and refusing those would turn a busy second into errors.
 */
public class Bulkhead {

    /** What a full bulkhead refuses with. Nothing was sent. */
    public static class FullException extends RuntimeException {

        public FullException(String name, int limit) {
            super(name + " already has " + limit + " calls waiting on it, so it was not asked");
        }
    }

    private final String name;
    private final int limit;
    private final Duration waitForATurn;
    private final Semaphore turns;

    public Bulkhead(String name, int limit, Duration waitForATurn) {
        if (limit < 1) {
            throw new IllegalArgumentException("a bulkhead that lets nothing through is an outage");
        }
        this.name = name;
        this.limit = limit;
        this.waitForATurn = waitForATurn;
        this.turns = new Semaphore(limit, true);
    }

    /**
     * Runs the call if there is room for it.
     *
     * @throws FullException without calling anything, when no turn came up in time
     */
    public <T> T call(Supplier<T> work) {
        boolean entered;
        try {
            entered = turns.tryAcquire(waitForATurn.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FullException(name, limit);
        }
        if (!entered) {
            throw new FullException(name, limit);
        }

        try {
            return work.get();
        } finally {
            turns.release();
        }
    }

    /** How many calls are waiting on it now, for anything that wants to show it. */
    public int inFlight() {
        return limit - turns.availablePermits();
    }
}
