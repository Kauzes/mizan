package dev.kauzes.mizan.test;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shared by every integration test in a JVM. Each one starts on first use and
 * is never stopped, so a suite pays the startup cost once rather than per test class.
 * Testcontainers reaps them when the JVM exits.
 */
public final class MizanContainers {

    private MizanContainers() {
    }

    public static PostgreSQLContainer postgres() {
        return Postgres.INSTANCE;
    }

    public static KafkaContainer kafka() {
        return Kafka.INSTANCE;
    }

    private static final class Postgres {

        private static final PostgreSQLContainer INSTANCE =
                new PostgreSQLContainer(DockerImageName.parse(PlatformImages.postgres()))
                        .withDatabaseName("mizan")
                        .withUsername("mizan")
                        .withPassword("mizan");

        static {
            INSTANCE.start();
        }
    }

    private static final class Kafka {

        private static final KafkaContainer INSTANCE =
                new KafkaContainer(DockerImageName.parse(PlatformImages.kafka()));

        static {
            INSTANCE.start();
        }
    }
}
