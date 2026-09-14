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
 * A service publishes from an outbox if and only if it owns one.
 *
 * <p>Both directions have failed quietly. A service that owns an outbox and does not publish
 * writes events that nobody is ever told about — the failure MIZ-48 exists to prevent. A service
 * that publishes without owning one runs a relay against a table that is not there: from MIZ-50
 * until MIZ-79 notification-service and settlement-service did exactly that, once a second, with
 * an ERROR each time, and it went unnoticed until the first run with JSON logs counted the lines.
 *
 * <p>So the list is read from the migrations rather than kept by hand: owning an outbox means a
 * migration creates the table.
 */
class OutboxOwnershipTest {

    @Test
    void thereIsAtLeastOneOutboxToPublish() {
        assertThat(services()).anyMatch(OutboxOwnershipTest::ownsAnOutbox);
    }

    @Test
    void everyServiceThatOwnsAnOutboxPublishesIt() {
        services().stream()
                .filter(OutboxOwnershipTest::ownsAnOutbox)
                .forEach(service -> assertThat(configurationOf(service).lines().toList())
                        .as("%s creates an outbox table, so it must publish from it or its "
                                + "events are never announced", name(service))
                        .containsSubsequence("mizan:", "  outbox:", "    publish: true"));
    }

    @Test
    void noServiceThatOwnsNoOutboxRunsARelay() {
        services().stream()
                .filter(service -> !ownsAnOutbox(service))
                .forEach(service -> assertThat(configurationOf(service))
                        .as("%s has no outbox table, and a relay there fails every second",
                                name(service))
                        .doesNotContain("publish: true"));
    }

    private static boolean ownsAnOutbox(Path service) {
        Path migrations = service.resolve("src/main/resources/db/migration");
        if (!Files.isDirectory(migrations)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(migrations)) {
            return files.filter(path -> path.toString().endsWith(".sql"))
                    .anyMatch(path -> read(path).toLowerCase().contains("create table outbox_event"));
        } catch (IOException couldNotWalk) {
            throw new IllegalStateException("could not read " + migrations, couldNotWalk);
        }
    }

    private static List<Path> services() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(RepositoryRoot.path().resolve("services"))) {
            modules.sorted()
                    .filter(module -> Files.exists(
                            module.resolve("src/main/resources/application.yml")))
                    .forEach(found::add);
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list the service modules", couldNotList);
        }
        return found;
    }

    private static String name(Path service) {
        return service.getFileName().toString();
    }

    private static String configurationOf(Path service) {
        return read(service.resolve("src/main/resources/application.yml"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + path, couldNotRead);
        }
    }
}
