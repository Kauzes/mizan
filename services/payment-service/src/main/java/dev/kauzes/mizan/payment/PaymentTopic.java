package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.web.outbox.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic this service publishes to, declared rather than conjured.
 *
 * <p>The broker has automatic topic creation turned off, deliberately. A topic that appears
 * because somebody published to a name is a topic with default partitioning nobody chose, and
 * a typo in a producer becomes a new topic rather than an error. Declaring it here means the
 * shape is a decision, and a consumer subscribing to a name nobody produces to fails loudly.
 */
@Configuration
public class PaymentTopic {

    /**
     * Three partitions, not one.
     *
     * <p>Ordering is per payment and is guaranteed by the key, so partitions are free to
     * multiply: more of them means more consumers can work at once without any of them seeing
     * one payment's events out of order. One partition would put a ceiling of exactly one
     * consumer on every consumer group forever, and partitions cannot be reduced later without
     * breaking the ordering that already went out.
     *
     * <p>One replica, because the local stack has one broker. A deployment with more sets this
     * from configuration; the number is here so that the question is visible rather than
     * defaulted.
     */
    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.of("payment"))
                .partitions(3)
                .replicas(1)
                .build();
    }
}
