package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A dashboard is a file in this repository, and this is what keeps it honest.
 *
 * <p>The failure worth designing against is specific: a panel whose query names a metric that
 * does not exist renders as an empty chart, and an empty chart is indistinguishable from a
 * quiet platform. Nobody finds out until somebody opens the dashboard during an incident,
 * which is the worst possible moment to learn that the thing they came to look at was never
 * there.
 *
 * <p>What is checked here is what can be checked without a running platform: that the files
 * parse, that every panel says which question it answers, that every query points at the
 * datasource this repository provisions, that no panel puts two scales on one pair of axes,
 * and that every {@code mizan_} metric a panel asks for is one a service actually registers.
 * That last one is the typo catcher, and it works because both halves are in this repository.
 *
 * <p>Metrics this platform does not own — {@code up}, the JVM's, Spring's, Hikari's — cannot be
 * checked from source, because whether they exist is a fact about a running service rather than
 * about any file here. Smoke step 20 asks the running Prometheus the same question of every
 * name in every dashboard, which covers those and also proves Grafana loaded the files at all.
 */
class DashboardsTest {

    private static final Path DASHBOARDS = RepositoryRoot.path().resolve("deploy/local/grafana");

    /** The uid the provisioned datasource declares, which every panel must ask for by name. */
    private static final String DATASOURCE = "mizan-prometheus";

    /**
     * PromQL's own vocabulary, which looks exactly like a metric name to a regular expression.
     * Everything left over after these is a metric, which is the point.
     */
    private static final Set<String> PROMQL = Set.of(
            "sum", "min", "max", "avg", "count", "count_values", "stddev", "stdvar",
            "topk", "bottomk", "quantile", "group",
            "by", "without", "on", "ignoring", "group_left", "group_right",
            "rate", "irate", "increase", "delta", "idelta", "deriv", "predict_linear",
            "resets", "changes", "abs", "ceil", "floor", "round", "clamp", "clamp_max",
            "clamp_min", "exp", "ln", "log2", "log10", "sqrt", "sgn",
            "absent", "absent_over_time", "histogram_quantile", "label_replace",
            "label_join", "time", "timestamp", "vector", "scalar", "sort", "sort_desc",
            "sum_over_time", "avg_over_time", "min_over_time", "max_over_time",
            "count_over_time", "last_over_time", "offset", "and", "or", "unless", "bool");

    private final List<Dashboard> dashboards = readDashboards();

    @Test
    void thereAreDashboards() {
        // Two, answering two different questions asked by two different people at two
        // different times. A single dashboard that tries to be both is the forty panel one
        // nobody reads.
        assertThat(dashboards).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void everyDashboardIsIdentifiedBySomethingStable() {
        dashboards.forEach(dashboard -> {
            assertThat(dashboard.uid())
                    .as("%s needs a uid, or its link changes every time it is provisioned",
                            dashboard.file())
                    .isNotBlank();
            assertThat(dashboard.title()).as("%s needs a title", dashboard.file()).isNotBlank();
        });

        assertThat(dashboards.stream().map(Dashboard::uid).distinct())
                .as("two dashboards sharing a uid means one of them silently replaces the other")
                .hasSize(dashboards.size());
    }

    @Test
    void everyPanelSaysWhichQuestionItAnswers() {
        // A panel whose title is a metric name is a panel that assumes the reader already
        // knows why they should care, which is never true of the person who opened this at
        // three in the morning.
        eachPanel((dashboard, panel) -> {
            assertThat(panel.path("title").asText(""))
                    .as("a panel in %s has no title", dashboard.file())
                    .isNotBlank();
            assertThat(panel.path("description").asText(""))
                    .as("panel \"%s\" in %s should say what it is for",
                            panel.path("title").asText(), dashboard.file())
                    .isNotBlank();
        });
    }

    @Test
    void everyQueryAsksTheDatasourceThisRepositoryProvisions() {
        // By uid rather than by "whichever one is default". A second datasource added later
        // must not be able to silently repoint every panel on the platform.
        eachPanel((dashboard, panel) -> assertThat(panel.path("datasource").path("uid").asText(""))
                .as("panel \"%s\" in %s should name the provisioned datasource",
                        panel.path("title").asText(), dashboard.file())
                .isEqualTo(DATASOURCE));

        String provisioned = read(DASHBOARDS.resolve("provisioning/datasources/prometheus.yml"));
        assertThat(provisioned)
                .as("the datasource file should declare the uid every panel asks for")
                .contains("uid: " + DATASOURCE);
    }

    @Test
    void noPanelPutsTwoScalesOnOnePairOfAxes() {
        // Two measures of different magnitude sharing a y-axis is a way to make any two lines
        // look related, and the relationship is an artefact of the scaling. Where two units
        // genuinely both matter, they get two panels — which is why counts and ages are
        // separate on the money dashboard.
        eachPanel((dashboard, panel) -> {
            String serialised = panel.toString();
            assertThat(serialised)
                    .as("panel \"%s\" in %s looks like it has a second axis",
                            panel.path("title").asText(), dashboard.file())
                    .doesNotContain("\"axisPlacement\":\"right\"")
                    .doesNotContain("\"yaxes\"");
        });
    }

    @Test
    void everyMetricThisPlatformOwnsIsOneAServiceRegisters() {
        Set<String> registered = metricsTheServicesRegister();

        assertThat(registered)
                .as("the meters registered in the service sources should have been found")
                .isNotEmpty();

        dashboards.forEach(dashboard -> dashboard.metrics().stream()
                .filter(metric -> metric.startsWith("mizan_"))
                .forEach(metric -> assertThat(registered)
                        .as("%s asks for %s, which no service registers. A panel querying a "
                                + "metric that does not exist renders as an empty chart, and "
                                + "an empty chart looks exactly like a quiet platform.",
                                dashboard.file(), metric)
                        .contains(metric)));
    }

    @Test
    void everyDashboardFileIsOneGrafanaIsToldToRead() {
        String provider = read(DASHBOARDS.resolve("provisioning/dashboards/mizan.yml"));
        Matcher path = Pattern.compile("path: (\\S+)").matcher(provider);

        assertThat(path.find()).as("the dashboard provider should name a directory").isTrue();
        String inTheContainer = path.group(1);

        String compose = RepositoryRoot.read("docker-compose.yml");
        assertThat(compose)
                .as("the directory the provider reads should be the one Compose mounts")
                .contains("./deploy/local/grafana/dashboards:" + inTheContainer)
                .contains("./deploy/local/grafana/provisioning:/etc/grafana/provisioning");

        // And the dashboard Grafana opens on has to be one of the files, not a path that was
        // right when it was typed.
        Matcher home = Pattern.compile("DEFAULT_HOME_DASHBOARD_PATH: (\\S+)").matcher(compose);
        if (home.find()) {
            String file = home.group(1).substring(home.group(1).lastIndexOf('/') + 1);
            assertThat(dashboards.stream().map(Dashboard::file))
                    .as("Grafana opens on %s, which is not one of the dashboards", file)
                    .contains(file);
        }
    }

    // -- reading the files ---------------------------------------------------------------

    private interface PanelCheck {
        void check(Dashboard dashboard, JsonNode panel);
    }

    private void eachPanel(PanelCheck check) {
        dashboards.forEach(dashboard -> dashboard.panels().forEach(
                panel -> check.check(dashboard, panel)));
    }

    private record Dashboard(String file, String uid, String title, List<JsonNode> panels,
            Set<String> metrics) {
    }

    private static List<Dashboard> readDashboards() {
        ObjectMapper json = new ObjectMapper();
        List<Dashboard> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(DASHBOARDS.resolve("dashboards"))) {
            for (Path file : files.sorted().toList()) {
                if (!file.toString().endsWith(".json")) {
                    continue;
                }
                JsonNode document;
                try {
                    document = json.readTree(file.toFile());
                } catch (IOException notJson) {
                    throw new IllegalStateException(file + " is not valid JSON", notJson);
                }
                List<JsonNode> panels = new ArrayList<>();
                document.path("panels").forEach(panels::add);
                found.add(new Dashboard(
                        file.getFileName().toString(),
                        document.path("uid").asText(""),
                        document.path("title").asText(""),
                        panels,
                        metricsIn(panels)));
            }
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list the dashboards", couldNotList);
        }
        return found;
    }

    /**
     * The metric names a set of panels asks for.
     *
     * <p>Label selectors and durations are removed first, so that a label called {@code status}
     * and a window called {@code 5m} are not mistaken for metrics. What is left is identifiers,
     * and everything that is not part of PromQL's own vocabulary is a metric name.
     */
    private static Set<String> metricsIn(List<JsonNode> panels) {
        Set<String> metrics = new LinkedHashSet<>();
        for (JsonNode panel : panels) {
            for (JsonNode target : panel.path("targets")) {
                // Label selectors, windows, and the labels a by() groups on. All three look
                // exactly like a metric name to a regular expression, and none of them is one.
                String expression = target.path("expr").asText("")
                        .replaceAll("\\{[^}]*}", " ")
                        .replaceAll("\\[[^]]*]", " ")
                        .replaceAll("\\b(?:by|without|on|ignoring|group_left|group_right)"
                                + "\\s*\\([^)]*\\)", " ");
                Matcher identifiers = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*")
                        .matcher(expression);
                while (identifiers.find()) {
                    String identifier = identifiers.group();
                    if (!PROMQL.contains(identifier)) {
                        metrics.add(identifier);
                    }
                }
            }
        }
        return metrics;
    }

    /**
     * Every meter this platform registers, named the way Prometheus will expose it.
     *
     * <p>Read out of the service sources rather than listed here, so a metric renamed in Java
     * breaks the dashboard that asks for the old name on the same commit. Micrometer turns the
     * dots into underscores, and gives a counter a {@code _total} suffix.
     */
    private static Set<String> metricsTheServicesRegister() {
        Pattern declared = Pattern.compile("\"(mizan(?:\\.[a-z]+)+)\"");
        Set<String> registered = new LinkedHashSet<>();

        try (Stream<Path> sources = Files.walk(RepositoryRoot.path().resolve("services"))) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(DashboardsTest::isProductionSource)
                    .forEach(path -> {
                        Matcher names = declared.matcher(read(path));
                        while (names.find()) {
                            String prometheus = names.group(1).replace('.', '_');
                            registered.add(prometheus);
                            registered.add(prometheus + "_total");
                        }
                    });
        } catch (IOException couldNotWalk) {
            throw new IllegalStateException("could not read the service sources", couldNotWalk);
        }
        return registered;
    }

    /** Production sources only: a metric named in a test proves nothing about what runs. */
    private static boolean isProductionSource(Path path) {
        for (int element = 0; element + 1 < path.getNameCount(); element++) {
            if (path.getName(element).toString().equals("src")
                    && path.getName(element + 1).toString().equals("main")) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + path, couldNotRead);
        }
    }
}
