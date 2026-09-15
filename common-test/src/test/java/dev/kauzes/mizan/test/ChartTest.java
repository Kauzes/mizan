package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Helm chart and the services it installs cannot drift apart.
 *
 * <p>The chart is a second description of the platform, next to Compose, and a second
 * description is a second place to forget the ninth service, give a service the wrong port, or
 * install one without the credential it needs to start. None of that shows up until an install,
 * and a failed install on a cluster is a much slower way to find a typo than a test.
 *
 * <p>Everything here is read out of the repository: the service modules, their own configuration,
 * and the chart's values. Rendering the chart is {@code scripts/check-chart.sh}, which needs Helm
 * and runs in CI.
 */
class ChartTest {

    private static final Path VALUES =
            RepositoryRoot.path().resolve("deploy/helm/mizan/values.yaml");

    /** Every credential the platform has, by the environment variable a service reads it from. */
    private static final Map<String, String> CREDENTIALS = Map.of(
            "MIZAN_DB_PASSWORD", "databasePassword",
            "MIZAN_INTERNAL_SERVICE_TOKEN", "internalServiceToken",
            "MIZAN_API_KEY_ENCRYPTION_KEY", "apiKeyEncryptionKey",
            "MIZAN_WEBHOOK_ENCRYPTION_KEY", "webhookEncryptionKey",
            "MIZAN_JWT_PRIVATE_KEY", "jwtPrivateKey");

    private final String values = read(VALUES);
    private final Map<String, String> servicesInTheChart = servicesIn(values);

    @Test
    void theChartInstallsEveryServiceAndNothingElse() {
        assertThat(new TreeSet<>(servicesInTheChart.keySet()))
                .as("values.yaml should list exactly the service modules in this repository")
                .isEqualTo(new TreeSet<>(serviceModules()));
    }

    @Test
    void everyServiceIsGivenThePortItListensOn() {
        servicesInTheChart.forEach((service, block) -> {
            Matcher chartPort = Pattern.compile("\\n    port: (\\d+)").matcher(block);
            assertThat(chartPort.find()).as("%s has no port in the chart", service).isTrue();

            Matcher ownPort = Pattern.compile("server:\\s*\\n\\s*port: (\\d+)")
                    .matcher(configurationOf(service));
            assertThat(ownPort.find())
                    .as("%s's application.yml should say which port it listens on", service)
                    .isTrue();

            assertThat(chartPort.group(1))
                    .as("the chart sends %s traffic to a port it does not listen on", service)
                    .isEqualTo(ownPort.group(1));
        });
    }

    @Test
    void everyCredentialAServiceReadsIsOneTheChartGivesIt() {
        // A service that reads a credential the chart does not hand it starts, and fails on the
        // first request that needs it — or, worse, falls back to a local default.
        servicesInTheChart.forEach((service, block) -> {
            String own = configurationOf(service);
            CREDENTIALS.forEach((variable, key) -> {
                if (own.contains("${" + variable)) {
                    assertThat(block)
                            .as("%s reads %s, so the chart must give it %s", service, variable, key)
                            .containsPattern("credentials: \\[[^\\]]*\\b" + key + "\\b");
                }
            });
        });
    }

    @Test
    void noCredentialIsWrittenIntoPlainConfiguration() {
        // Anything under a service's env lands in a ConfigMap, which is readable by anybody who
        // can read the namespace and is printed by every kubectl describe.
        CREDENTIALS.keySet().forEach(variable -> assertThat(values)
                .as("%s belongs in the Secret, not in a ConfigMap", variable)
                .doesNotContain(variable + ":"));
    }

    @Test
    void noCredentialAndNoImageTagHasADefault() {
        // A default credential is either obviously fake or quietly becomes the one a deployment
        // runs on. A default tag is "latest" by another name.
        Matcher block = Pattern.compile("\\ncredentials:\\n((?:  .*\\n|\\s*\\n)+)").matcher(values);
        assertThat(block.find()).isTrue();
        block.group(1).lines()
                .map(String::trim)
                .filter(line -> !line.startsWith("#") && line.contains(":"))
                .forEach(line -> assertThat(line)
                        .as("a credential in values.yaml has a value: %s", line)
                        .endsWith("\"\""));

        assertThat(values).containsPattern("\\n  tag: \"\"\\n");
    }

    @Test
    void everyPodTheChartCanRunFitsInsidePostgresesConnectionLimit() {
        // Hikari opens its whole pool at start, so a pod's connections are its pool size from the
        // moment it exists, not only under load. Counted at the most pods the chart can run: an
        // autoscaler's maximum, not its minimum, because the last pod to scale up is the one that
        // is refused with "too many clients" at the moment traffic is highest.
        int limit = numberOr(values, "(?m)^    maxConnections: (\\d+)", 100);
        int reserved = numberOr(values, "(?m)^    reservedConnections: (\\d+)", 10);
        int budget = limit - reserved;

        int total = 0;
        StringBuilder breakdown = new StringBuilder();
        for (Map.Entry<String, String> service : servicesInTheChart.entrySet()) {
            String block = service.getValue();
            if (!Pattern.compile("(?m)^    database: ").matcher(block).find()) {
                continue;
            }
            int pods = numberOr(block, "(?m)^      maxReplicas: (\\d+)",
                    numberOr(block, "(?m)^    replicas: (\\d+)", 1));
            // Hikari's own default when the chart does not say.
            int pool = numberOr(block, "(?m)^    databasePool: (\\d+)", 10);
            total += pods * pool;
            breakdown.append(String.format("%n  %s: %d pod(s) x %d = %d",
                    service.getKey(), pods, pool, pods * pool));
        }

        assertThat(total)
                .as("the chart can open %d connections against a budget of %d (%d less %d kept "
                        + "for migrations and a person with psql):%s",
                        total, budget, limit, reserved, breakdown)
                .isLessThanOrEqualTo(budget);
    }

    @Test
    void callsWaitingOnTheAcquirerLeaveConnectionsForEverythingElse() {
        // ADR 0052. An authorization holds a connection while it waits on the acquirer. If as many
        // may wait as the pool has connections, a slow acquirer takes them all and a merchant
        // reading a payment waits behind the bank, which is what the limit exists to prevent.
        int checked = 0;
        for (Map.Entry<String, String> service : servicesInTheChart.entrySet()) {
            String block = service.getValue();
            Matcher limit = Pattern.compile("(?m)^      MIZAN_ACQUIRER_MAX_CONCURRENT_CALLS: \"?(\\d+)")
                    .matcher(block);
            if (!limit.find()) {
                continue;
            }
            int pool = numberOr(block, "(?m)^    databasePool: (\\d+)", 10);
            assertThat(Integer.parseInt(limit.group(1)))
                    .as("%s lets %s calls wait on the acquirer with a pool of %d",
                            service.getKey(), limit.group(1), pool)
                    .isLessThan(pool);
            checked++;
        }
        assertThat(checked).as("payment-service sets the limit").isPositive();
    }

    private static int numberOr(String text, String pattern, int otherwise) {
        Matcher found = Pattern.compile(pattern).matcher(text);
        return found.find() ? Integer.parseInt(found.group(1)) : otherwise;
    }

    private static Map<String, String> servicesIn(String values) {
        int start = values.indexOf("\nservices:\n");
        assertThat(start).as("values.yaml should have a services block").isPositive();
        String services = values.substring(start + "\nservices:\n".length());

        Map<String, String> found = new LinkedHashMap<>();
        Matcher names = Pattern.compile("(?m)^  ([a-z-]+):\\s*$").matcher(services);
        List<int[]> spans = new java.util.ArrayList<>();
        List<String> order = new java.util.ArrayList<>();
        while (names.find()) {
            order.add(names.group(1));
            spans.add(new int[] {names.start(), names.end()});
        }
        for (int at = 0; at < order.size(); at++) {
            int end = at + 1 < spans.size() ? spans.get(at + 1)[0] : services.length();
            found.put(order.get(at), services.substring(spans.get(at)[1], end));
        }
        return found;
    }

    private static List<String> serviceModules() {
        try (Stream<Path> modules = Files.list(RepositoryRoot.path().resolve("services"))) {
            return modules
                    .filter(module -> Files.exists(
                            module.resolve("src/main/resources/application.yml")))
                    .map(module -> module.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list the service modules", couldNotList);
        }
    }

    private static String configurationOf(String service) {
        return read(RepositoryRoot.path()
                .resolve("services")
                .resolve(service)
                .resolve("src/main/resources/application.yml"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).replace("\r\n", "\n");
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + path, couldNotRead);
        }
    }
}
