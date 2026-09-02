package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * That an auto-configuration written here is one Spring will actually read.
 *
 * <p>A class annotated {@code @AutoConfiguration} does nothing at all unless it is also named
 * in {@code AutoConfiguration.imports}. Nothing warns about the gap: the class compiles, the
 * annotation is right, and the beans simply never exist. Whether that is noticed depends
 * entirely on whether something injects one of them — a service that only ever writes through
 * such a bean starts perfectly happily and quietly does nothing.
 *
 * <p>This platform has already been bitten by that shape twice. MIZ-41 had idempotency
 * inactive on every write while the suite stayed green, because a condition was evaluated too
 * early. MIZ-47 added an outbox whose auto-configuration was not in this file, and got away
 * with it only because a bean depended on it and the context refused to start.
 *
 * <p>So the file is checked against the classes rather than trusted to match them.
 */
class AutoConfigurationRegistrationTest {

    private static final Path IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    @Test
    void everyAutoConfigurationHereIsRegistered() throws IOException {
        List<String> registered = Files.readAllLines(IMPORTS, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        List<String> written = Arrays.stream(MizanWebAutoConfiguration.class.getDeclaredClasses())
                .filter(nested -> nested.isAnnotationPresent(AutoConfiguration.class))
                .map(Class::getName)
                .sorted()
                .toList();

        assertThat(written)
                .as("no auto-configurations found at all, which means this test has stopped "
                        + "checking anything")
                .isNotEmpty();

        assertThat(registered)
                .as("an @AutoConfiguration that is not in %s is not read by Spring, and its "
                        + "beans never exist. Nothing says so: the class compiles and the "
                        + "mechanism is simply absent", IMPORTS.getFileName())
                .containsAll(written);
    }

    @Test
    void everyRegisteredAutoConfigurationExists() throws IOException, ClassNotFoundException {
        // The other direction. A class that was renamed or removed leaves a line here that
        // Spring will fail on at startup, in a message about a missing class rather than
        // about this file.
        for (String name : Files.readAllLines(IMPORTS, StandardCharsets.UTF_8)) {
            String line = name.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            assertThat(Class.forName(line)).as("%s is registered but not found", line).isNotNull();
        }
    }
}
