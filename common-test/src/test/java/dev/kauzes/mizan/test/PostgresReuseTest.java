package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A second class touching Postgres, so reuse is actually exercised across classes. */
@Tag("integration")
class PostgresReuseTest {

    @Test
    void reusesTheContainerStartedByTheOtherClass() {
        SharedContainerRecorder.recordAndAssertSame(
                "postgres", MizanContainers.postgres().getContainerId());
    }

    @Test
    void theAccessorAlwaysHandsBackOneInstance() {
        assertThat(MizanContainers.postgres()).isSameAs(MizanContainers.postgres());
    }
}
