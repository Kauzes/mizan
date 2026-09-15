package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Three numbers that only work in one order, and one rule that only works if nobody breaks it.
 *
 * <p>The numbers: the preStop pause, the application's shutdown phase, and the pod's grace period.
 * The grace period has to be longer than the other two added up, or the kubelet kills the process
 * while it is still finishing somebody's request. Each number lives in a different file — a Helm
 * values file and a Java constant — which is exactly how they drift apart.
 *
 * <p>The rule: liveness never includes a dependency. A liveness probe that fails when Postgres is
 * unreachable restarts every pod of every service at once, during the outage restarting cannot fix.
 */
class LifecycleWiringTest {

    @Test
    void theGracePeriodCoversThePauseAndTheShutdownPhaseWithRoomToSpare() {
        String values = RepositoryRoot.read("deploy/helm/mizan/values.yaml");
        int preStop = number(values, "preStopSeconds: (\\d+)");
        int grace = number(values, "terminationGracePeriodSeconds: (\\d+)");

        String stops = RepositoryRoot.read(
                "common-web/src/main/java/dev/kauzes/mizan/common/web/lifecycle/"
                        + "HowThisPlatformStops.java");
        int phase = number(stops, "SHUTDOWN_PHASE = \"(\\d+)s\"");

        assertThat(preStop).as("a pod should pause so it leaves the Service first").isPositive();
        // Room to spare, not an exact fit: the JVM has to exit after the last phase ends.
        assertThat(grace)
                .as("grace %ds must exceed preStop %ds plus shutdown phase %ds, with room to exit",
                        grace, preStop, phase)
                .isGreaterThanOrEqualTo(preStop + phase + 5);
    }

    @Test
    void composeWaitsAsLongAsTheClusterDoes() {
        // Compose kills a container 10s after asking it to stop unless told otherwise, which is
        // half the shutdown phase: a plain docker stop would cut off the work a rolling deploy
        // on the cluster lets finish. The same decision, in the other place the platform runs.
        String compose = RepositoryRoot.read("docker-compose.yml");
        String stops = RepositoryRoot.read(
                "common-web/src/main/java/dev/kauzes/mizan/common/web/lifecycle/"
                        + "HowThisPlatformStops.java");
        int phase = number(stops, "SHUTDOWN_PHASE = \"(\\d+)s\"");

        Matcher graces = Pattern.compile("stop_grace_period: (\\d+)s").matcher(compose);
        int found = 0;
        while (graces.find()) {
            found++;
            assertThat(Integer.parseInt(graces.group(1)))
                    .as("a Compose stop grace shorter than the shutdown phase cuts work off")
                    .isGreaterThanOrEqualTo(phase + 5);
        }
        assertThat(found)
                .as("every service Compose builds should wait for its in-flight work")
                .isEqualTo(services().size());
    }

    @Test
    void noServiceMakesBeingAliveDependOnSomethingElse() {
        services().forEach(configuration -> {
            Matcher liveness = Pattern.compile("liveness:\\s*\\n\\s*include: ([^\\n]+)")
                    .matcher(read(configuration));
            if (liveness.find()) {
                assertThat(liveness.group(1).trim())
                        .as("%s makes liveness depend on %s, which restarts every pod during a "
                                + "dependency outage", configuration.getParent().getParent()
                                .getParent().getParent().getFileName(), liveness.group(1))
                        .isEqualTo("livenessState");
            }
        });
    }

    @Test
    void theLifecycleDefaultsAreActuallyContributed() {
        assertThat(RepositoryRoot.read("common-web/src/main/resources/META-INF/spring.factories"))
                .as("a post-processor that is not registered is a post-processor that never runs")
                .contains("dev.kauzes.mizan.common.web.lifecycle.HowThisPlatformStops");
    }

    private static int number(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        assertThat(matcher.find()).as("expected to find %s", pattern).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static List<Path> services() {
        try (Stream<Path> modules = Files.list(RepositoryRoot.path().resolve("services"))) {
            return modules
                    .map(module -> module.resolve("src/main/resources/application.yml"))
                    .filter(Files::exists)
                    .toList();
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list the service modules", couldNotList);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).replace("\r\n", "\n");
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + path, couldNotRead);
        }
    }
}
