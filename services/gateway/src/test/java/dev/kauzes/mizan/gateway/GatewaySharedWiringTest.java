package dev.kauzes.mizan.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.web.ReactiveCorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The gateway is reactive while every other service is servlet based, so the shared web
 * module has to contribute the reactive half here and none of the servlet half. The
 * servlet advice extends a Spring MVC class that is not even on this classpath.
 */
@SpringBootTest
class GatewaySharedWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contributesTheReactiveCorrelationFilter() {
        assertThat(context.getBeansOfType(ReactiveCorrelationIdFilter.class)).hasSize(1);
    }

    @Test
    void contributesNoServletBeans() {
        assertThat(context.containsBean("correlationIdFilter")).isFalse();
        assertThat(context.containsBean("globalExceptionHandler")).isFalse();
    }
}
