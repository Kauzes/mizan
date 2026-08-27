package dev.kauzes.mizan.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

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
    }
}
