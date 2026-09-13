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
 * {@link ScheduledWorkTest} only guards the services that remember to extend it.
 *
 * <p>Which is the same shape of bug it was written to catch. A service gains its first
 * scheduled method, nobody adds the guard, nobody adds {@code @EnableScheduling}, and the work
 * silently never runs while every test stays green — the failure this platform has now met
 * four times, most recently when ledger-service started checking its own books on a timer.
 *
 * <p>So the list is derived rather than kept. Every module whose production code schedules
 * anything must both enable scheduling and extend the guard. Nothing about this compiles, and
 * nothing else would notice.
 */
class ScheduledWorkIsGuardedTest {

    private final List<Path> schedulers = modulesThatScheduleWork();

    @Test
    void thereIsScheduledWorkToGuard() {
        assertThat(schedulers)
                .as("the modules that schedule work should have been found")
                .isNotEmpty();
    }

    @Test
    void everyServiceThatSchedulesWorkEnablesScheduling() {
        schedulers.forEach(module -> assertThat(mainSourcesOf(module))
                .as("%s schedules work but nothing in it enables scheduling, so those methods "
                        + "never run. Add @EnableScheduling to its application class.",
                        name(module))
                .anyMatch(source -> read(source).contains("@EnableScheduling")));
    }

    @Test
    void everyServiceThatSchedulesWorkProvesItAtRuntime() {
        // The annotation being present is not the claim. What is asserted by extending
        // ScheduledWorkTest is that the assembled context actually holds scheduled tasks,
        // which is the thing that was false all three previous times.
        schedulers.forEach(module -> assertThat(testSourcesOf(module))
                .as("%s schedules work, so it should extend ScheduledWorkTest — otherwise "
                        + "nothing checks that its timers were registered", name(module))
                .anyMatch(source -> read(source).contains("extends ScheduledWorkTest")));
    }

    private static String name(Path module) {
        return module.getFileName().toString();
    }

    private static List<Path> modulesThatScheduleWork() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(RepositoryRoot.path().resolve("services"))) {
            modules.sorted()
                    .filter(Files::isDirectory)
                    .filter(module -> mainSourcesOf(module).stream()
                            .anyMatch(source -> read(source).contains("@Scheduled")))
                    .forEach(found::add);
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list the service modules", couldNotList);
        }
        return found;
    }

    private static List<Path> mainSourcesOf(Path module) {
        return javaUnder(module.resolve("src/main/java"));
    }

    private static List<Path> testSourcesOf(Path module) {
        return javaUnder(module.resolve("src/test/java"));
    }

    private static List<Path> javaUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException couldNotWalk) {
            throw new IllegalStateException("could not read " + root, couldNotWalk);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + source, couldNotRead);
        }
    }
}
