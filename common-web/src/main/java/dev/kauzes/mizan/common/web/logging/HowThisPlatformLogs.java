package dev.kauzes.mizan.common.web.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * One place decides what a log line looks like, so a new service gets it by existing.
 *
 * <p>This was eight copies of the same three lines of YAML, which is eight chances for the
 * ninth service to be the one that forgets — and a service whose lines are shaped differently
 * from everybody else's is a service that quietly falls out of every search somebody runs
 * during an incident.
 *
 * <p>Two shapes, chosen by where it is running rather than by what it is.
 *
 * <ul>
 *   <li><b>Readable</b>, by default, because the usual reader of these is a person watching a
 *       terminal. JSON read with the eyes is not a log, it is a puzzle.
 *   <li><b>ECS JSON</b> when {@code MIZAN_LOG_FORMAT} says so, which is what a deployment sets
 *       and what CI runs with. Every MDC value becomes a field, so the correlation id and the
 *       trace id are queryable rather than buried in a string a parser has to guess at.
 * </ul>
 *
 * <p>Both carry both ids. They answer different questions and neither replaces the other: the
 * correlation id is what a merchant reads out over the telephone, the trace id is what opens
 * the trace. ADR 0043.
 *
 * <p>Defaults, not overrides. Every value here is contributed at the lowest precedence, so a
 * service that has a reason to differ says so in its own configuration and wins.
 */
public class HowThisPlatformLogs implements EnvironmentPostProcessor, Ordered {

    /** Set to {@code ecs} for machine readable output. Empty or absent means readable. */
    static final String FORMAT = "MIZAN_LOG_FORMAT";

    private static final String SOURCE = "mizan-logging-defaults";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {

        Map<String, Object> defaults = new LinkedHashMap<>();

        // Both ids on every readable line, in the one place Boot leaves for exactly this.
        // The dashes are what a missing value looks like, which is information: a line with
        // no correlation id was written by something that is not serving a request.
        defaults.put("logging.pattern.correlation", "[%X{correlationId:--} %X{traceId:--}] ");

        String format = environment.getProperty(FORMAT, "");
        if (!format.isBlank()) {
            defaults.put("logging.structured.format.console", format);
            // ECS wants to know whose logs these are. Without it every line in a shipped
            // store says only that something on this platform wrote it.
            defaults.put(
                    "logging.structured.ecs.service.name", "${spring.application.name:mizan}");
        }

        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
    }

    @Override
    public int getOrder() {
        // After the application's own configuration has been read, and before logging is
        // initialised. Contributing at the end of the source list keeps these as defaults.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
