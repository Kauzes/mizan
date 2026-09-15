package dev.kauzes.mizan.common.web.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * How every service stops, and what "alive" means for every service, decided in one place.
 *
 * <p>Two decisions that are easy to get wrong one service at a time.
 *
 * <p><b>Stopping finishes the work already started.</b> A pod being replaced stops taking new
 * requests and gives the ones in flight up to {@link #SHUTDOWN_PHASE} to finish. The number is
 * chosen against the slowest thing a request does — an authorization waits at most half a second
 * on risk, five on the acquirer and five on the ledger — and against the pod's grace period in
 * the chart, which must be longer than this plus the preStop pause or Kubernetes kills the
 * process mid-answer. LifecycleWiringTest holds the two numbers in that order.
 *
 * <p><b>Alive means the process can do anything at all, and nothing more.</b> Liveness never
 * includes the database or Kafka. A liveness probe that fails when Postgres is unreachable
 * restarts every pod of every service at once, during exactly the outage that restarting cannot
 * fix, and turns a dependency blip into a platform-wide restart storm. Readiness is the question
 * that includes dependencies, and its consequence is gentler: no traffic until it recovers.
 *
 * <p>Defaults at the lowest precedence, so a service with a reason to differ says so and wins.
 */
public class HowThisPlatformStops implements EnvironmentPostProcessor, Ordered {

    /** How long the in-flight work of one shutdown phase is given. See the chart's grace period. */
    public static final String SHUTDOWN_PHASE = "20s";

    private static final String SOURCE = "mizan-lifecycle-defaults";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {

        Map<String, Object> defaults = new LinkedHashMap<>();
        // Boot's default already, and said out loud anyway: "immediate" drops every request in
        // flight on every rolling deploy, and a default is one release note away from changing.
        defaults.put("server.shutdown", "graceful");
        defaults.put("spring.lifecycle.timeout-per-shutdown-phase", SHUTDOWN_PHASE);
        // The process, and only the process.
        defaults.put("management.endpoint.health.group.liveness.include", "livenessState");
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
