package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.web.CorrelationIdFilter;
import dev.kauzes.mizan.common.web.CorrelationPropagationInterceptor;
import dev.kauzes.mizan.common.web.GlobalExceptionHandler;
import dev.kauzes.mizan.common.web.ReactiveCorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** A servlet service should pick up the shared error and correlation wiring by depending on it. */
@SpringBootTest
class PaymentSharedWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contributesTheServletWiring() {
        assertThat(context.getBeansOfType(CorrelationIdFilter.class)).hasSize(1);
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
        assertThat(context.getBeansOfType(CorrelationPropagationInterceptor.class)).hasSize(1);
    }

    @Test
    void contributesNoReactiveBeans() {
        assertThat(context.getBeansOfType(ReactiveCorrelationIdFilter.class)).isEmpty();
    }
}
