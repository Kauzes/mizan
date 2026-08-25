package dev.kauzes.mizan.test;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for a test that needs real infrastructure. The property suppliers run only when
 * something actually asks for the property, so a service with no Kafka on its classpath
 * never starts the Kafka container.
 */
@Tag("integration")
public abstract class MizanIntegrationTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MizanContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> MizanContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> MizanContainers.postgres().getPassword());
        registry.add(
                "spring.kafka.bootstrap-servers",
                () -> MizanContainers.kafka().getBootstrapServers());
    }
}
