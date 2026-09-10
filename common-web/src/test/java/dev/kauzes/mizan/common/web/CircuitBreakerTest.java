package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Giving up early on purpose, and knowing when to try again. */
class CircuitBreakerTest {

    @Test
    void letsCallsThroughWhileTheyWork() {
        CircuitBreaker breaker = new CircuitBreaker("thing", 3, Duration.ofMinutes(1));

        for (int i = 0; i < 10; i++) {
            assertThat(breaker.<String>call(() -> "fine")).isEqualTo("fine");
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterEnoughFailuresAndThenStopsAsking() {
        AtomicInteger asked = new AtomicInteger();
        CircuitBreaker breaker = new CircuitBreaker("thing", 3, Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                        asked.incrementAndGet();
                        throw new IllegalStateException("no");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(asked).hasValue(3);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // The point of the whole thing: a hundred more payments cost nothing rather than a
        // hundred timeouts.
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                        asked.incrementAndGet();
                        return "never reached";
                    }))
                    .isInstanceOf(CircuitBreaker.CircuitOpenException.class);
        }
        assertThat(asked)
                .as("nothing was asked while it was open")
                .hasValue(3);
    }

    @Test
    void triesOnceAfterTheWaitAndClosesIfItWorks() {
        CircuitBreaker breaker = new CircuitBreaker("thing", 2, Duration.ofMillis(50));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                        throw new IllegalStateException("no");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        sleep(80);

        assertThat(breaker.<String>call(() -> "answering again")).isEqualTo("answering again");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.consecutiveFailures()).isZero();
    }

    @Test
    void opensAgainIfTheOneTryStillFails() {
        AtomicInteger asked = new AtomicInteger();
        CircuitBreaker breaker = new CircuitBreaker("thing", 2, Duration.ofMillis(50));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                        asked.incrementAndGet();
                        throw new IllegalStateException("no");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        sleep(80);

        // Exactly one call gets through to find out, and it fails, so it shuts again.
        assertThatThrownBy(() -> breaker.call(() -> {
                    asked.incrementAndGet();
                    throw new IllegalStateException("still no");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(asked).hasValue(3);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> breaker.call(() -> "never reached"))
                .isInstanceOf(CircuitBreaker.CircuitOpenException.class);
        assertThat(asked)
                .as("and refuses everything again without asking")
                .hasValue(3);
    }

    @Test
    void oneSuccessAmongFailuresResetsTheCount() {
        CircuitBreaker breaker = new CircuitBreaker("thing", 3, Duration.ofMinutes(1));

        // Consecutive, not cumulative. Something that fails one call in ten is not down, and a
        // breaker that counted every failure forever would eventually open on anything.
        for (int round = 0; round < 5; round++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                        throw new IllegalStateException("no");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> breaker.call(() -> {
                        throw new IllegalStateException("no");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            breaker.call(() -> "fine");
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void refusesWithSomethingACallerCanTellApart() {
        CircuitBreaker breaker = new CircuitBreaker("risk", 1, Duration.ofMinutes(1));
        assertThatThrownBy(() -> breaker.call(() -> {
                    throw new IllegalStateException("no");
                }))
                .isInstanceOf(IllegalStateException.class);

        // A caller deciding what to do when a guard is unavailable has to be able to tell
        // "it said no" from "it was never asked".
        assertThatThrownBy(() -> breaker.call(() -> "never reached"))
                .isInstanceOf(CircuitBreaker.CircuitOpenException.class)
                .hasMessageContaining("risk is not answering, so it was not asked");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
