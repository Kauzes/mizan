package dev.kauzes.mizan.notification;

import dev.kauzes.mizan.common.web.inbox.EventErrorHandling;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** How this service listens, and what it does with a message it cannot handle. */
@Configuration
public class EventConsumption {

    /**
     * Retry a few times, then set the message aside and carry on.
     *
     * <p>Picked up by Boot's Kafka autoconfiguration as the error handler for every listener
     * in this service, so a new consumer gets this behaviour by existing rather than by
     * somebody remembering to ask for it.
     */
    @Bean
    DefaultErrorHandler eventErrorHandler(
            KafkaTemplate<String, String> kafka,
            @Value("${mizan.events.retries:3}") int retries,
            @Value("${mizan.events.first-retry:500ms}") Duration firstRetry,
            @Value("${mizan.events.longest-retry:10s}") Duration longestRetry) {

        return EventErrorHandling.retryThenSetAside(kafka, retries, firstRetry, longestRetry);
    }

    /**
     * The dead letter topic, declared because the broker will not create it.
     *
     * <p>One partition and no key ordering to preserve: nothing consumes this in order, and
     * what reads it writes each message to a row of its own.
     */
    @Bean
    NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(EventErrorHandling.deadLetterTopicFor("mizan.payment.events"))
                .partitions(1)
                .replicas(1)
                .build();
    }
}
