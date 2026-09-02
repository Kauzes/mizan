package dev.kauzes.mizan.common.web;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Wires the shared web behaviour into any service that depends on this module. The servlet
 * and reactive halves are separate because the gateway is reactive and the rest are not.
 */
public class MizanWebAutoConfiguration {

    @AutoConfiguration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    public static class Servlet {

        @Bean
        @ConditionalOnMissingBean
        public CorrelationIdFilter correlationIdFilter() {
            return new CorrelationIdFilter();
        }

        @Bean
        @ConditionalOnMissingBean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        @ConditionalOnMissingBean
        public CorrelationPropagationInterceptor correlationPropagationInterceptor() {
            return new CorrelationPropagationInterceptor();
        }

        @Bean
        @ConditionalOnMissingBean
        public AuthorizationInterceptor authorizationInterceptor() {
            return new AuthorizationInterceptor();
        }

        @Bean
        @ConditionalOnMissingBean
        public AuthorizationDeclarations authorizationDeclarations(
                ObjectProvider<RequestMappingHandlerMapping> mappings) {
            return new AuthorizationDeclarations(mappings);
        }

        @Bean
        @ConditionalOnMissingBean
        public IdempotencyDeclarations idempotencyDeclarations(
                ObjectProvider<RequestMappingHandlerMapping> mappings) {
            return new IdempotencyDeclarations(mappings);
        }

        /**
         * Puts the authorization check in front of every handler, and lets one ask for the
         * caller as an argument. Registered here rather than left to each service, so an
         * endpoint is guarded by existing rather than by somebody remembering.
         */
        @Bean
        @ConditionalOnMissingBean(name = "mizanAuthorizationConfigurer")
        public WebMvcConfigurer mizanAuthorizationConfigurer(
                AuthorizationInterceptor authorization) {

            return new WebMvcConfigurer() {

                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    // Only this platform's own API. Actuator, the generated spec and the
                    // published signing keys are not endpoints anybody annotates, and
                    // refusing them for saying nothing would be refusing them for not being
                    // controllers of ours.
                    registry.addInterceptor(authorization).addPathPatterns("/api/**").order(0);
                }

                @Override
                public void addArgumentResolvers(
                        List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new CallerArgumentResolver());
                }
            };
        }
    }

    /**
     * The outbox, for a service that has both a database to write it to and a mapper to
     * serialise a payload with.
     *
     * <p>Ordered after the JDBC auto-configurations for the reason MIZ-41 found the hard way:
     * a {@code @ConditionalOnBean} evaluated before the bean it asks about has been defined
     * is simply false, and the mechanism it guards then does not exist while everything goes
     * on looking correct.
     */
    @AutoConfiguration(
            afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
            })
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnBean({
        org.springframework.jdbc.core.JdbcTemplate.class,
        tools.jackson.databind.ObjectMapper.class
    })
    public static class Events {

        @Bean
        @ConditionalOnMissingBean
        public dev.kauzes.mizan.common.web.outbox.Outbox outbox(
                org.springframework.jdbc.core.JdbcTemplate jdbc,
                tools.jackson.databind.ObjectMapper json) {

            return new dev.kauzes.mizan.common.web.outbox.Outbox(jdbc, json);
        }

        /**
         * The consuming side. Separate from the outbox in every way but the module: it writes
         * to its own table, in the opposite transactional arrangement, for reasons written
         * out on the class.
         */
        @Bean
        @ConditionalOnMissingBean
        public dev.kauzes.mizan.common.web.inbox.Inbox inbox(
                org.springframework.jdbc.core.JdbcTemplate jdbc,
                tools.jackson.databind.ObjectMapper json,
                org.springframework.transaction.PlatformTransactionManager transactions) {

            return new dev.kauzes.mizan.common.web.inbox.Inbox(jdbc, json, transactions);
        }
    }

    /**
     * The half that publishes, for a service that has a broker to publish to.
     *
     * <p>Separate from the outbox itself so that writing events and sending them stay
     * independent: a service can record events with no Kafka on its classpath at all, which
     * is what MIZ-47 did on purpose for a whole story.
     */
    @AutoConfiguration(
            afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
                "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
            })
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    @ConditionalOnBean({
        org.springframework.jdbc.core.JdbcTemplate.class,
        org.springframework.kafka.core.KafkaTemplate.class
    })
    public static class EventPublishing {

        @Bean
        @ConditionalOnMissingBean
        public dev.kauzes.mizan.common.web.outbox.EventPublisher eventPublisher(
                org.springframework.kafka.core.KafkaTemplate<String, String> kafka,
                tools.jackson.databind.ObjectMapper json,
                @Value("${mizan.outbox.publish-timeout:10s}") java.time.Duration timeout) {

            return new dev.kauzes.mizan.common.web.outbox.KafkaEventPublisher(
                    kafka, json, timeout);
        }

        @Bean
        @ConditionalOnMissingBean
        public dev.kauzes.mizan.common.web.outbox.OutboxRelay outboxRelay(
                org.springframework.jdbc.core.JdbcTemplate jdbc,
                dev.kauzes.mizan.common.web.outbox.EventPublisher publisher,
                org.springframework.transaction.PlatformTransactionManager transactions,
                @Value("${mizan.outbox.batch-size:100}") int batchSize,
                @Value("${mizan.outbox.first-retry:1s}") java.time.Duration firstRetry,
                @Value("${mizan.outbox.longest-retry:5m}") java.time.Duration longestRetry) {

            return new dev.kauzes.mizan.common.web.outbox.OutboxRelay(
                    jdbc, publisher, transactions, batchSize, firstRetry, longestRetry);
        }

        @Bean
        @ConditionalOnMissingBean
        public dev.kauzes.mizan.common.web.outbox.OutboxRelaySchedule outboxRelaySchedule(
                dev.kauzes.mizan.common.web.outbox.OutboxRelay relay) {

            return new dev.kauzes.mizan.common.web.outbox.OutboxRelaySchedule(relay);
        }
    }

    /**
     * The half of idempotency that needs somewhere to write. A service with no database
     * still gets the annotations and the startup check, and any endpoint it marks
     * idempotent would have nowhere to record a result, which the check cannot know and
     * the missing bean makes obvious at once.
     */
    @AutoConfiguration(
            afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
            })
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnBean(org.springframework.jdbc.core.JdbcTemplate.class)
    public static class Idempotency {

        @Bean
        @ConditionalOnMissingBean
        public IdempotencyStore idempotencyStore(
                org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new IdempotencyStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public IdempotencyFilter idempotencyFilter(
                @Value("${mizan.idempotency.maximum-body:1048576}") int maximumBody) {
            return new IdempotencyFilter(maximumBody);
        }

        @Bean
        @ConditionalOnMissingBean
        public IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore store) {
            return new IdempotencyInterceptor(store);
        }

        @Bean
        @ConditionalOnMissingBean(name = "mizanIdempotencyConfigurer")
        public WebMvcConfigurer mizanIdempotencyConfigurer(
                IdempotencyInterceptor idempotency) {

            return new WebMvcConfigurer() {

                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    // After authorization: an unauthorized request should not be able to
                    // claim a key, and a refusal should not be recorded as an outcome.
                    registry.addInterceptor(idempotency)
                            .addPathPatterns("/api/**")
                            .order(10);
                }
            };
        }
    }

    @AutoConfiguration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public static class Reactive {

        @Bean
        @ConditionalOnMissingBean
        public ReactiveCorrelationIdFilter reactiveCorrelationIdFilter() {
            return new ReactiveCorrelationIdFilter();
        }
    }

    /**
     * The shared half of the API contract, contributed to whichever service is generating a
     * spec. A service without springdoc on its classpath is documenting nothing and gets
     * nothing.
     */
    @AutoConfiguration
    @ConditionalOnClass(name = "org.springdoc.core.customizers.OpenApiCustomizer")
    public static class OpenApi {

        @Bean
        @ConditionalOnMissingBean
        public MizanOpenApiCustomizer mizanOpenApiCustomizer(
                @Value("${spring.application.name:mizan}") String applicationName,
                @Value("${mizan.openapi.version:0.1.0}") String version) {
            return new MizanOpenApiCustomizer(applicationName, version);
        }

        @Bean
        @ConditionalOnMissingBean
        public RequiredPermissionCustomizer requiredPermissionCustomizer() {
            return new RequiredPermissionCustomizer();
        }

        @Bean
        @ConditionalOnMissingBean
        public IdempotencyCustomizer idempotencyCustomizer() {
            return new IdempotencyCustomizer();
        }
    }
}
