package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the container id the first test class to ask sees, and fails any later class that
 * sees a different one. Order independent, so it proves reuse whichever class runs first.
 */
final class SharedContainerRecorder {

    private static final Map<String, String> FIRST_SEEN = new ConcurrentHashMap<>();

    private SharedContainerRecorder() {
    }

    static void recordAndAssertSame(String name, String containerId) {
        String previous = FIRST_SEEN.putIfAbsent(name, containerId);
        if (previous != null) {
            assertThat(containerId)
                    .as("%s container should be reused across test classes", name)
                    .isEqualTo(previous);
        }
    }
}
