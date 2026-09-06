package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * That a service with scheduled work actually runs it.
 *
 * <p>{@code @Scheduled} does nothing at all unless something in the context enables scheduling.
 * Nothing warns about the gap: the bean exists, the annotation is right, and the method is
 * simply never called. Whether that is noticed depends entirely on whether a test happens to
 * drive the method directly — and every test that drives it directly will pass.
 *
 * <p>This platform has now met that shape three times. MIZ-41 had idempotency inactive on every
 * write while the suite stayed green. MIZ-47 had an auto-configuration that was never
 * registered. MIZ-55 had a webhook dispatcher that was a bean, was correct, and never ran,
 * found only because a live check against the real stack noticed the deliveries piling up.
 *
 * <p>Extended by a service that schedules anything, so the third time is the last.
 *
 * <p>An integration test, because a service that schedules work has a database and will not
 * start without one. Not extending this base let it pass on a laptop with Compose running and
 * fail in CI, which is its own small lesson about what "it passed locally" is worth.
 */
public abstract class ScheduledWorkTest extends MizanIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void everyScheduledMethodIsActuallyScheduled() {
        Map<String, ScheduledAnnotationBeanPostProcessor> processors =
                context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class);

        assertThat(processors)
                .as("no scheduling is enabled at all, so every @Scheduled method in this "
                        + "service is a method that never runs. Add @EnableScheduling.")
                .isNotEmpty();

        List<Object> scheduled = processors.values().stream()
                .flatMap(processor -> processor.getScheduledTasks().stream())
                .map(Object.class::cast)
                .toList();

        assertThat(scheduled)
                .as("scheduling is enabled and nothing is scheduled, which means either this "
                        + "service has no scheduled work — in which case it should not be "
                        + "running this test — or its @Scheduled methods are not being seen")
                .isNotEmpty();
    }
}
