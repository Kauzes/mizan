package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A few calls may wait on something at once, and the rest are not sent. */
class BulkheadTest {

    @Test
    @Timeout(10)
    void refusesTheCallBeyondTheLimitWithoutSendingIt() throws Exception {
        Bulkhead bulkhead = new Bulkhead("thing", 2, Duration.ofMillis(50));
        CountDownLatch waiting = new CountDownLatch(2);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();

        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                callers.submit(() -> bulkhead.call(() -> {
                    sent.incrementAndGet();
                    waiting.countDown();
                    await(answer);
                    return "answered";
                }));
            }
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bulkhead.inFlight()).isEqualTo(2);

            long started = System.nanoTime();
            assertThatThrownBy(() -> bulkhead.call(() -> {
                        sent.incrementAndGet();
                        return "never";
                    }))
                    .isInstanceOf(Bulkhead.FullException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("refused after a brief wait for a turn, not after the slow calls finish")
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(sent).as("the third was never sent").hasValue(2);

            answer.countDown();
        }
        assertThat(bulkhead.inFlight()).as("turns are given back").isZero();
    }

    @Test
    void aTurnIsGivenBackWhenTheCallFails() {
        Bulkhead bulkhead = new Bulkhead("thing", 1, Duration.ZERO);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> bulkhead.call(() -> {
                        throw new IllegalStateException("no");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(bulkhead.<String>call(() -> "fine")).isEqualTo("fine");
    }

    @Test
    @Timeout(10)
    void aShortOverlapWaitsForItsTurnRatherThanFailing() throws Exception {
        Bulkhead bulkhead = new Bulkhead("thing", 1, Duration.ofSeconds(2));
        CountDownLatch inside = new CountDownLatch(1);

        try (ExecutorService callers = Executors.newSingleThreadExecutor()) {
            callers.submit(() -> bulkhead.call(() -> {
                inside.countDown();
                sleep(100);
                return "first";
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(bulkhead.<String>call(() -> "second")).isEqualTo("second");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
