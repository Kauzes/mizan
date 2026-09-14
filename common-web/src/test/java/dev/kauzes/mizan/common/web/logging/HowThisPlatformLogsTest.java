package dev.kauzes.mizan.common.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * One place decides what a log line looks like.
 *
 * <p>It was eight copies of the same YAML, which is eight chances for the ninth service to be
 * the one that forgets — and a service whose lines are shaped differently from everybody
 * else's is a service that falls out of every search somebody runs during an incident.
 *
 * <p>Two shapes, chosen by where it runs rather than by what it is: readable for the person
 * watching a terminal, JSON for the store that will be queried. Both carry both ids, because
 * they answer different questions and a line with only one of them is a line that answers half
 * of what is being asked.
 */
class HowThisPlatformLogsTest {

    private final HowThisPlatformLogs logging = new HowThisPlatformLogs();

    @Test
    void everyLineCarriesBothIds() {
        MockEnvironment environment = new MockEnvironment();

        logging.postProcessEnvironment(environment, new SpringApplication());

        String pattern = environment.getProperty("logging.pattern.correlation");
        assertThat(pattern)
                .as("the correlation id is what a merchant reads out over the telephone")
                .contains("correlationId")
                .as("the trace id is what opens the trace")
                .contains("traceId");
    }

    @Test
    void readableByDefaultBecauseAPersonIsUsuallyWatching() {
        MockEnvironment environment = new MockEnvironment();

        logging.postProcessEnvironment(environment, new SpringApplication());

        // JSON read with the eyes is not a log, it is a puzzle. A developer running this
        // stack gets the ordinary Boot output.
        assertThat(environment.getProperty("logging.structured.format.console")).isNull();
    }

    @Test
    void jsonWhereSomethingIsGoingToQueryIt() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(HowThisPlatformLogs.FORMAT, "ecs");
        environment.setProperty("spring.application.name", "payment-service");

        logging.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        // Named, because without it every line in a shipped store says only that something on
        // this platform wrote it — and "which service" is the first thing anybody filters on.
        assertThat(environment.getProperty("logging.structured.ecs.service.name"))
                .isEqualTo("payment-service");
    }

    @Test
    void andAServiceWithAReasonToDifferStillWins() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.pattern.correlation", "[%X{correlationId:-}] ");

        logging.postProcessEnvironment(environment, new SpringApplication());

        // Contributed as defaults, at the end of the source list. A shared decision that
        // cannot be overridden is a shared decision somebody works around by other means.
        assertThat(environment.getProperty("logging.pattern.correlation"))
                .isEqualTo("[%X{correlationId:-}] ");
    }
}
