package dev.kauzes.mizan.test;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for a test that needs real infrastructure. The property suppliers run only when
 * something actually asks for the property, so a service with no Kafka on its classpath
 * never starts the Kafka container.
 *
 * <p>The datasource is wired by an initializer rather than by a supplier here, because it
 * has to know which database the service owns and that is a property of the service, not
 * of this class.
 *
 * <p>MockMvc is configured here rather than on the tests that call it, so every test of a
 * service shares one application context instead of starting a second one to get it.
 */
@Tag("integration")
@AutoConfigureMockMvc
@ContextConfiguration(initializers = ServiceDatabaseInitializer.class)
public abstract class MizanIntegrationTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.bootstrap-servers",
                () -> MizanContainers.kafka().getBootstrapServers());
    }
}
