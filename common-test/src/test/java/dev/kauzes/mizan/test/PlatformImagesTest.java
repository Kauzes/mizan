package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Reads .env off disk and compares it to what the build handed the test JVM. Asserting the
 * system property against itself would prove nothing; this proves the wiring from the file
 * Compose reads through to the harness.
 */
class PlatformImagesTest {

    @Test
    void everyImageMatchesTheFileComposeReads() throws IOException {
        Map<String, String> env = readDotEnv();

        assertThat(PlatformImages.postgres()).isEqualTo(env.get("POSTGRES_IMAGE"));
        assertThat(PlatformImages.kafka()).isEqualTo(env.get("KAFKA_IMAGE"));
        assertThat(PlatformImages.redis()).isEqualTo(env.get("REDIS_IMAGE"));
    }

    @Test
    void theFileNamesEveryImageThePlatformRuns() throws IOException {
        assertThat(readDotEnv())
                .containsKeys("POSTGRES_IMAGE", "KAFKA_IMAGE", "REDIS_IMAGE", "PROMETHEUS_IMAGE");
    }

    @Test
    void composeReferencesTheVariablesRatherThanHardCodedTags() throws IOException {
        // Read out of Compose rather than listed here, so an image added later is covered the
        // day it is added. A hard coded tag in Compose is how the platform comes to run one
        // version while the tests run another.
        Matcher images = Pattern.compile("image: ([^\n]+)").matcher(
                RepositoryRoot.read("docker-compose.yml"));

        Map<String, String> env = readDotEnv();
        while (images.find()) {
            String image = images.group(1).trim();
            assertThat(image)
                    .as("every image should come from .env, and %s does not", image)
                    .matches("[$][{][A-Z_]+[}]");
            assertThat(env)
                    .containsKey(image.substring(2, image.length() - 1));
        }
    }

    private static Map<String, String> readDotEnv() throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(RepositoryRoot.path().resolve(".env"))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            values.put(
                    trimmed.substring(0, trimmed.indexOf('=')).trim(),
                    trimmed.substring(trimmed.indexOf('=') + 1).trim());
        }
        return values;
    }
}
