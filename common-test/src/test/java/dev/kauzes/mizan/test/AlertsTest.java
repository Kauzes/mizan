package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

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
 * What wakes somebody up, checked like code.
 *
 * <p>A rule with a typo in a metric name never fires, and a rule that never fires looks exactly
 * like a platform that is fine. So every metric a rule names that this platform owns must be one
 * a service registers, read out of both halves of this repository on the same commit.
 *
 * <p>And every rule has to say what a person does about it. An alert whose runbook is "ask the
 * person who wrote it" has a single point of failure, and that person is asleep too.
 *
 * <p>Whether each expression parses is asked of promtool itself, by smoke step 23, because the
 * only honest parser of PromQL is Prometheus's own.
 */
class AlertsTest {

    private static final Path RULES = RepositoryRoot.path().resolve("deploy/local/alerts.yml");

    /** The two a platform that holds money cannot do without. */
    private static final Set<String> NON_NEGOTIABLE =
            Set.of("LedgerDoesNotBalance", "AnEventWasSetAside");

    private static final Set<String> PROMQL = Set.of(
            "sum", "min", "max", "avg", "count", "by", "without", "on", "ignoring",
            "group_left", "group_right", "rate", "irate", "increase", "delta", "abs",
            "absent", "and", "or", "unless", "bool", "offset", "histogram_quantile",
            "clamp_min", "clamp_max", "time", "vector", "scalar");

    private final String rules = read(RULES);
    private final List<Rule> all = rulesIn(rules);

    @Test
    void theRulesAreFewAndTheNonNegotiableOnesAreAmongThem() {
        assertThat(all).extracting(Rule::name).containsAll(NON_NEGOTIABLE);
        // The discipline is the opposite of a rule per metric.
        assertThat(all).as("a rule per metric is a pager nobody answers").hasSizeLessThan(12);
    }

    @Test
    void everyRuleSaysHowBadAndWhatToDo() {
        all.forEach(rule -> {
            assertThat(rule.body())
                    .as("%s should say whether it pages somebody or waits for the morning",
                            rule.name())
                    .containsPattern("severity: (page|ticket)");
            assertThat(rule.body()).as("%s should say what is wrong", rule.name())
                    .contains("summary:");
            assertThat(rule.body())
                    .as("%s should say what a person does about it, in its own words",
                            rule.name())
                    .contains("action:");
        });
    }

    @Test
    void everyMetricThisPlatformOwnsIsOneAServiceRegisters() {
        Set<String> registered = metricsTheServicesRegister();
        assertThat(registered).isNotEmpty();

        all.forEach(rule -> metricsIn(rule.expression()).stream()
                .filter(metric -> metric.startsWith("mizan_"))
                .forEach(metric -> assertThat(registered)
                        .as("%s asks for %s, which no service registers, so it never fires",
                                rule.name(), metric)
                        .contains(metric)));
    }

    @Test
    void prometheusIsToldToLoadThem() {
        assertThat(read(RepositoryRoot.path().resolve("deploy/local/prometheus.yml")))
                .contains("rule_files:")
                .contains("/etc/prometheus/alerts.yml");
        assertThat(RepositoryRoot.read("docker-compose.yml"))
                .contains("./deploy/local/alerts.yml:/etc/prometheus/alerts.yml:ro");
    }

    private record Rule(String name, String expression, String body) {
    }

    /** One entry per "- alert:" block, with the expression and the text up to the next rule. */
    private static List<Rule> rulesIn(String text) {
        List<Rule> found = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int at = 0; at < lines.size(); at++) {
            String line = lines.get(at).trim();
            if (!line.startsWith("- alert:")) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            StringBuilder expression = new StringBuilder();
            boolean inExpression = false;
            int next = at + 1;
            for (; next < lines.size() && !lines.get(next).trim().startsWith("- alert:")
                    && !lines.get(next).trim().startsWith("- name:"); next++) {
                String each = lines.get(next);
                body.append(each).append('\n');
                String trimmed = each.trim();
                if (trimmed.startsWith("expr:")) {
                    expression.append(trimmed.substring(5)).append(' ');
                    inExpression = trimmed.endsWith("|");
                } else if (inExpression) {
                    if (trimmed.contains(":") && !trimmed.contains("{") && !trimmed.contains("(")
                            && !trimmed.contains("[")) {
                        inExpression = false;
                    } else {
                        expression.append(trimmed).append(' ');
                    }
                }
            }
            found.add(new Rule(
                    line.substring("- alert:".length()).trim(),
                    expression.toString(),
                    body.toString()));
        }
        return found;
    }

    private static Set<String> metricsIn(String expression) {
        String bare = expression
                .replaceAll("\\{[^}]*}", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("\\b(?:by|without|on|ignoring)\\s*\\([^)]*\\)", " ");
        Set<String> metrics = new LinkedHashSet<>();
        Matcher identifiers = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*").matcher(bare);
        while (identifiers.find()) {
            if (!PROMQL.contains(identifiers.group())) {
                metrics.add(identifiers.group());
            }
        }
        return metrics;
    }

    private static Set<String> metricsTheServicesRegister() {
        Pattern declared = Pattern.compile("\"(mizan(?:\\.[a-z]+)+)\"");
        Set<String> registered = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(RepositoryRoot.path().resolve("services"))) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(AlertsTest::isProductionSource)
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
