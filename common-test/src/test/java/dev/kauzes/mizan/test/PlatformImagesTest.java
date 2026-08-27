package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
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
                .containsKeys("POSTGRES_IMAGE", "KAFKA_IMAGE", "REDIS_IMAGE");
    }

    @Test
    void composeReferencesTheVariablesRatherThanHardCodedTags() throws IOException {
        String compose = RepositoryRoot.read("docker-compose.yml");

        assertThat(compose)
                .contains("image: ${POSTGRES_IMAGE}")
                .contains("image: ${KAFKA_IMAGE}")
                .contains("image: ${REDIS_IMAGE}");
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
