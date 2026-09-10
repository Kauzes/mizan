package dev.kauzes.mizan.risk;

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
 * How this service listens, and what it does with an event it cannot learn from.
 *
 * <p>The same arrangement MIZ-50 settled on for notification-service, reused rather than
 * reinvented: bounded retry, then set aside. Without it Spring's default handler retries nine
 * times as fast as it can and moves on, which turns one unreadable message into a burst of
 * errors and a silently skipped event — the two failure modes ADR 0025 exists to avoid.
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
     * <p>Separate from notification-service's, because the two consume the same events for
     * different reasons and one of them failing says nothing about the other. A shared dead
     * letter topic would mean a message set aside by risk being read by whatever consumes
     * notification's.
     */
    @Bean
    NewTopic riskDeadLetterTopic() {
        return TopicBuilder.name(
                        EventErrorHandling.deadLetterTopicFor("mizan.payment.events") + ".risk")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
