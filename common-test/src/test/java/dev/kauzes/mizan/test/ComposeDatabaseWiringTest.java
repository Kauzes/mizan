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
 * A service names the database it owns in its own configuration. Three other places have to
 * agree with that name, and none of them is checked by anything that compiles: the compose
 * environment, the wait condition that keeps Flyway from starting before Postgres is ready,
 * and the script that creates the databases the first time Postgres starts.
 *
 * <p>The services are discovered rather than listed, so a service added later is covered
 * here the day it configures a datasource.
 */
class ComposeDatabaseWiringTest {

    /** Character classes rather than escapes, so the pattern stays readable. */
    private static final Pattern DATASOURCE_URL =
            Pattern.compile("url: [$][{]MIZAN_DB_URL:jdbc:postgresql://[^/]+/([a-z0-9_]+)[}]");

    private final Map<String, String> databasesByService = servicesWithADatabase();

    @Test
    void someServiceOwnsADatabase() {
        assertThat(databasesByService).isNotEmpty();
    }

    @Test
    void composeHandsEachServiceTheDatabaseItConfigured() {
        String compose = RepositoryRoot.read("docker-compose.yml");

        databasesByService.forEach((service, database) -> assertThat(compose)
                .as("%s should be pointed at the %s database", service, database)
                .contains("MIZAN_DB_URL: jdbc:postgresql://postgres:5432/" + database));
    }

    @Test
    void composeMakesEachOfThemWaitForPostgres() {
        databasesByService.keySet().forEach(service -> assertThat(composeBlock(service))
                .as("%s should wait for a healthy Postgres", service)
                .contains("postgres:")
                .contains("condition: service_healthy"));
    }

    @Test
    void postgresCreatesEveryDatabaseOnFirstStart() {
        String script = RepositoryRoot.read("deploy/local/init-databases.sql").toLowerCase();

        databasesByService.values().forEach(database ->
                assertThat(script).contains("create database " + database + ";"));
    }

    /** The compose entry for one service, up to the line that starts the next one. */
    private static String composeBlock(String service) {
        String compose = RepositoryRoot.read("docker-compose.yml");
        int start = compose.indexOf("\n  " + service + ":");
        assertThat(start).as("no %s service in docker-compose.yml", service).isNotNegative();

        int end = nextServiceAfter(compose, start);
        return compose.substring(start, end);
    }

    private static int nextServiceAfter(String compose, int start) {
        int candidate = compose.indexOf("\n  ", start + 1);
        while (candidate != -1) {
            int firstCharacter = candidate + 3;
            if (firstCharacter < compose.length() && compose.charAt(firstCharacter) != ' ') {
                return candidate;
            }
            candidate = compose.indexOf("\n  ", candidate + 1);
        }
        return compose.length();
    }

    private static Map<String, String> servicesWithADatabase() {
        Map<String, String> found = new LinkedHashMap<>();
        Path services = RepositoryRoot.path().resolve("services");
        try (Stream<Path> modules = Files.list(services)) {
            modules.sorted().forEach(module -> {
                Path configuration = module.resolve("src/main/resources/application.yml");
                if (!Files.exists(configuration)) {
                    return;
                }
                Matcher url = DATASOURCE_URL.matcher(read(configuration));
                if (url.find()) {
                    found.put(module.getFileName().toString(), url.group(1));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("could not list the service modules", e);
        }
        return found;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
