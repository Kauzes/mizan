package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * That every service is traced, and that the rule about which traces are kept is the one
 * written down.
 *
 * <p>A service left out of this is invisible in exactly the way a service left out of the
 * scrape is (MetricsWiringTest): a trace that stops at a hop looks like a platform where that
 * hop is fast, and nobody investigating a slow payment thinks to ask whether the tracing is
 * complete. So the list is derived from the modules that exist rather than kept by hand.
 *
 * <p>The sampling rule gets its own assertions because it is the one thing here that is easy
 * to get quietly wrong. Every service exports every span, and the collector decides. That
 * arrangement exists for one reason: a service samples at the first span of a trace, which is
 * before the failure it would want to keep has happened. Turn the services' sampling down and
 * the whole thing still works, still produces traces, and silently stops keeping the ones
 * worth having.
 */
class TracingWiringTest {

    private static final Path COLLECTOR =
            RepositoryRoot.path().resolve("deploy/local/otel-collector.yml");

    private final List<String> services = serviceModules();

    @Test
    void everyServiceExportsItsSpans() {
        services.forEach(service -> {
            assertThat(buildOf(service))
                    .as("%s should have the tracing starter, or it makes no spans at all",
                            service)
                    .contains("spring-boot-starter-opentelemetry");
            assertThat(configurationOf(service))
                    .as("%s should say where to send them", service)
                    .contains("endpoint: ${MIZAN_OTLP:");
        });
    }

    @Test
    void everyServiceSamplesEverythingAndLetsTheCollectorDecide() {
        // Not a preference. Head sampling cannot keep the failures, because the decision is
        // made at the first span and the failure has not happened yet.
        services.forEach(service -> assertThat(configurationOf(service))
                .as("%s should export every span and leave the keeping to the collector",
                        service)
                .containsPattern("probability: \\$\\{MIZAN_TRACE_SAMPLE:1\\.0}"));
    }

    @Test
    void nobodyPushesMetricsAsWellAsScrapingThem() {
        // The tracing starter brings a metrics registry that pushes to a collector. Left on,
        // every number on this platform would arrive twice by two different routes, which is
        // how a dashboard comes to disagree with itself — and MIZ-75 already decided that this
        // platform is scraped. It also fills every log with 404s from an endpoint that does
        // not take metrics, which is how it was noticed at all.
        services.forEach(service -> assertThat(linesOf(configurationOf(service)))
                .as("%s should not push metrics as well as being scraped", service)
                .containsSubsequence(
                        "  otlp:",
                        "    metrics:",
                        "      export:",
                        "        enabled: false"));
    }

    /** Lines, with whatever line ending the file has on this machine taken out of it. */
    private static List<String> linesOf(String configuration) {
        return configuration.lines().toList();
    }

    @Test
    void everyServiceIsPointedAtTheCollectorRatherThanAtTheStore() {
        String compose = RepositoryRoot.read("docker-compose.yml");

        services.forEach(service -> assertThat(compose)
                .as("%s should export to the collector", service)
                .contains("MIZAN_OTLP: http://otel-collector:4318/v1/traces"));

        // Straight to Tempo would work and would quietly bypass the only place the rule can
        // be applied.
        assertThat(compose)
                .as("no service should export straight to the trace store")
                .doesNotContain("MIZAN_OTLP: http://tempo");
    }

    @Test
    void anythingThatFailedIsAlwaysKept() {
        String collector = read(COLLECTOR);

        assertThat(collector)
                .as("the collector should decide after the trace is finished")
                .contains("tail_sampling:");
        assertThat(collector)
                .as("a failed trace should be kept whatever the volume control says")
                .contains("type: status_code")
                .contains("status_codes: [ERROR]");

        // The percentage is the only policy a deployment turns down, and it must not be the
        // one the error rule depends on: they are separate policies, and any policy matching
        // keeps the trace.
        int errorPolicy = collector.indexOf("type: status_code");
        int percentagePolicy = collector.indexOf("type: probabilistic");
        assertThat(errorPolicy)
                .as("both policies should exist, independently of each other")
                .isGreaterThan(0);
        assertThat(percentagePolicy).isGreaterThan(errorPolicy);
    }

    @Test
    void theCollectorSendsWhatItKeepsToTheStore() {
        assertThat(read(COLLECTOR)).contains("endpoint: tempo:4317");

        String compose = RepositoryRoot.read("docker-compose.yml");
        assertThat(compose)
                .as("both configurations should come from this repository")
                .contains("./deploy/local/otel-collector.yml:/etc/otel/config.yml")
                .contains("./deploy/local/tempo.yml:/etc/tempo/tempo.yml");
    }

    @Test
    void aPersonCanOpenATrace() {
        // A trace nobody can look at is a trace that was not collected. Grafana is already
        // where the numbers are read, so it is where the traces are read too.
        String datasource =
                read(RepositoryRoot.path().resolve("deploy/local/grafana/provisioning/"
                        + "datasources/tempo.yml"));

        assertThat(datasource).contains("type: tempo").contains("url: http://tempo:3200");
    }

    private static String buildOf(String service) {
        return read(RepositoryRoot.path()
                .resolve("services")
                .resolve(service)
                .resolve("build.gradle.kts"));
    }

    private static String configurationOf(String service) {
        return read(RepositoryRoot.path()
                .resolve("services")
                .resolve(service)
                .resolve("src/main/resources/application.yml"));
    }

    private static List<String> serviceModules() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(RepositoryRoot.path().resolve("services"))) {
            modules.sorted()
                    .filter(module -> Files.exists(
                            module.resolve("src/main/resources/application.yml")))
                    .forEach(module -> found.add(module.getFileName().toString()));
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list the service modules", couldNotList);
        }
        return found;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + path, couldNotRead);
        }
    }
}
