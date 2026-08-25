package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class KafkaContainerTest {

    @Test
    void runsTheSameImageComposeRuns() {
        assertThat(MizanContainers.kafka().getDockerImageName())
                .isEqualTo(PlatformImages.kafka());
    }

    @Test
    void acceptsRealAdminCalls() throws ExecutionException, InterruptedException {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                MizanContainers.kafka().getBootstrapServers());

        try (Admin admin = Admin.create(config)) {
            admin.createTopics(List.of(new NewTopic("mizan.harness.probe", 1, (short) 1)))
                    .all()
                    .get();

            Set<String> topics = admin.listTopics().names().get();
            assertThat(topics).contains("mizan.harness.probe");
        }
    }

    @Test
    void isTheSameContainerEveryTestClassSees() {
        SharedContainerRecorder.recordAndAssertSame(
                "kafka", MizanContainers.kafka().getContainerId());
    }
}
