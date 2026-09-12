package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.web.inbox.EventErrorHandling;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * How this service listens, and what it does with a capture it cannot record.
 *
 * <p>The same arrangement as risk and notification, reused rather than reinvented: bounded
 * retry, then set aside for a person. It matters more here than in either of them, because a
 * capture this service never records is a payment a merchant is never paid for — so the one
 * thing that must not happen is a message quietly skipped, which is exactly what Spring's
 * default handler does after nine fast retries.
 */
@Configuration
public class EventConsumption {

    @Bean
    DefaultErrorHandler eventErrorHandler(
            KafkaTemplate<String, String> kafka,
            @Value("${mizan.events.retries:3}") int retries,
            @Value("${mizan.events.first-retry:500ms}") Duration firstRetry,
            @Value("${mizan.events.longest-retry:10s}") Duration longestRetry) {

        return EventErrorHandling.retryThenSetAside(kafka, retries, firstRetry, longestRetry);
    }

    /**
     * This service's own dead letter topic.
     *
     * <p>Its own, like every other consumer's. Three services read the payment events for
     * three different reasons, and one of them failing says nothing about the others.
     */
    @Bean
    NewTopic settlementDeadLetterTopic() {
        return TopicBuilder.name(
                        EventErrorHandling.deadLetterTopicFor("mizan.payment.events")
                                + ".settlement")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
