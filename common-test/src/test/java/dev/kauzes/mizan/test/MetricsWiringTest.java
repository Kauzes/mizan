package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Monitoring that has quietly stopped covering a service looks exactly like a service with
 * nothing wrong, which is the worst way for a check to fail: silence reads as good news.
 *
 * <p>So the list of things Prometheus scrapes is not trusted to be kept up to date by hand.
 * The services are discovered from the modules that exist, and three things have to agree with
 * that list, none of which is checked by anything that compiles: the scrape configuration, the
 * port each service actually listens on, and whether the service exposes the endpoint at all.
 *
 * <p>The eighth service being added and nobody remembering this file is the failure this test
 * exists for.
 */
class MetricsWiringTest {

    private static final Pattern SERVER_PORT = Pattern.compile("port: (\\d+)");

    /**
     * A service that keeps its actuator somewhere other than the port it serves from. The
     * gateway does, because the gateway is the edge: its numbers are not something to hand to
     * whoever can reach the API.
     */
    private static final Pattern MANAGEMENT_PORT = Pattern.compile(
            "management:.*?server:\\s*\\n\\s*port: [^:]*:(\\d+)[}]", Pattern.DOTALL);

    /** Every service module, and the port a scrape would have to go to. */
    private final Map<String, Integer> services = servicesAndTheirPorts();

    @Test
    void thereAreServicesToScrape() {
        assertThat(services).as("the service modules should have been found").hasSizeGreaterThan(5);
    }

    @Test
    void everyServiceOffersTheScrapeEndpoint() {
        services.keySet().forEach(service -> assertThat(configurationOf(service))
                .as("%s should expose prometheus, or nothing can collect what it measures",
                        service)
                .containsPattern("include: [^\\n]*prometheus"));
    }

    @Test
    void everyServiceSaysWhichServiceItsMetricsCameFrom() {
        // The scrape adds a label saying where it collected from. That is not the same claim:
        // a metric that only knows which address answered loses its meaning the moment
        // anything is aggregated, and every dashboard aggregates.
        services.keySet().forEach(service -> assertThat(configurationOf(service))
                .as("%s should tag its metrics with its own name", service)
                .contains("service: ${spring.application.name}"));
    }

    @Test
    void prometheusScrapesEveryServiceOnThePortItListensOn() {
        String scrape = RepositoryRoot.read("deploy/local/prometheus.yml");

        services.forEach((service, port) -> assertThat(scrape)
                .as("prometheus should scrape %s on %d", service, port)
                .contains("- " + service + ":" + port));
    }

    @Test
    void prometheusScrapesNothingThatIsNotAService() {
        String scrape = RepositoryRoot.read("deploy/local/prometheus.yml");
        Matcher targets = Pattern.compile("- ([a-z-]+):(\\d+)").matcher(scrape);

        while (targets.find()) {
            String target = targets.group(1);
            if (target.equals("localhost")) {
                // Prometheus watches itself, so "no data" can be told apart from "nothing was
                // collected". It is not one of this platform's services.
                continue;
            }
            assertThat(services)
                    .as("%s is scraped but is not a service in this repository", target)
                    .containsKey(target);
        }
    }

    @Test
    void composeRunsAPrometheusAgainstThatFile() {
        String compose = RepositoryRoot.read("docker-compose.yml");

        assertThat(compose)
                .as("the scrape configuration should come from the repository, not from a "
                        + "running instance somebody set up once")
                .contains("./deploy/local/prometheus.yml:/etc/prometheus/prometheus.yml:ro");
        assertThat(compose).contains("image: ${PROMETHEUS_IMAGE}");
    }

    @Test
    void everyServiceSaysWhatReadyMeansForIt() {
        // Three answers, not two: starting, ready, and broken. The default readiness group
        // says only that the context came up, so a service that started perfectly and cannot
        // reach its database would report itself ready — and a container probe would believe
        // it. Every service answers the question explicitly, including the ones whose answer
        // is that they have nothing external to be ready for.
        services.keySet().forEach(service -> {
            String configuration = configurationOf(service);
            assertThat(configuration)
                    .as("%s should enable the probes", service)
                    .contains("probes:");
            assertThat(configuration)
                    .as("%s should say what its readiness includes", service)
                    .containsPattern("readiness:\\s*\\n\\s*include: readinessState");

            if (configuration.contains("datasource:")) {
                assertThat(configuration)
                        .as("%s owns a database, so being ready should mean reaching it", service)
                        .contains("include: readinessState, db");
            }
        });
    }

    @Test
    void composeWaitsForReadinessRatherThanForALivePort() {
        String compose = RepositoryRoot.read("docker-compose.yml");

        services.keySet().forEach(service -> assertThat(compose)
                .as("%s's container probe should ask whether it is ready to do the work, "
                        + "not only whether it answers", service)
                .contains("/actuator/health/readiness"));
    }

    private static String configurationOf(String service) {
        return read(RepositoryRoot.path()
                .resolve("services")
                .resolve(service)
                .resolve("src/main/resources/application.yml"));
    }

    private static Map<String, Integer> servicesAndTheirPorts() {
        Map<String, Integer> found = new LinkedHashMap<>();
        try (Stream<Path> modules = Files.list(RepositoryRoot.path().resolve("services"))) {
            modules.sorted().forEach(module -> {
                Path configuration = module.resolve("src/main/resources/application.yml");
                if (!Files.exists(configuration)) {
                    return;
                }
                String text = read(configuration);
                Matcher management = MANAGEMENT_PORT.matcher(text);
                if (management.find()) {
                    found.put(
                            module.getFileName().toString(), Integer.parseInt(management.group(1)));
                    return;
                }
                Matcher port = SERVER_PORT.matcher(text);
                if (port.find()) {
                    found.put(module.getFileName().toString(), Integer.parseInt(port.group(1)));
                }
            });
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
