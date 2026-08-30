package dev.kauzes.mizan.common.web.declarations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import dev.kauzes.mizan.common.web.RequiresPermission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An endpoint nobody decided the rules for is the ordinary way an API grows a hole, and it is
 * invisible: it works, which is what it also looks like when it is correct. So the service
 * refuses to start, and this is the test that says so.
 *
 * <p>In a package of its own because the applications below are annotated as Spring Boot
 * applications, and a test elsewhere in this module looking for its own configuration would
 * otherwise find three of them.
 */
class AuthorizationDeclarationsTest {

    @Test
    void refusesToStartWhenAnEndpointSaysNothingAboutWhoMayCallIt() {
        assertThatThrownBy(() -> run(Undeclared.class))
                .as("the failure should name the endpoint that has to be fixed")
                .hasStackTraceContaining("say nothing about who may call them")
                .hasStackTraceContaining("nobodyDecided");
    }

    @Test
    void startsWhenEveryEndpointHasDecided() {
        assertThatCode(() -> {
            try (ConfigurableApplicationContext context = run(Declared.class)) {
                assertThat(context.isRunning()).isTrue();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void ignoresEndpointsThatAreNotOurs() {
        // Actuator and the generated documentation are mapped outside /api/ and are nobody's
        // controllers to annotate. A service full of them still starts.
        assertThatCode(() -> {
            try (ConfigurableApplicationContext context = run(OutsideTheApi.class)) {
                assertThat(context.isRunning()).isTrue();
            }
        }).doesNotThrowAnyException();
    }

    private static ConfigurableApplicationContext run(Class<?> application) {
        return new SpringApplicationBuilder(application)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
    }

    @SpringBootApplication
    static class Undeclared {

        @RestController
        @RequestMapping("/api/v1/undeclared")
        static class Controller {

            @GetMapping
            String nobodyDecided() {
                return "this should never have started";
            }
        }
    }

    @SpringBootApplication
    static class Declared {

        @RestController
        @RequestMapping("/api/v1/declared")
        static class Controller {

            @GetMapping("/guarded")
            @RequiresPermission(Permission.MERCHANT_READ)
            String guarded() {
                return "guarded";
            }

            @GetMapping("/open")
            @PublicEndpoint(because = "a test needs one deliberate example")
            String open() {
                return "open";
            }
        }
    }

    @SpringBootApplication
    static class OutsideTheApi {

        @RestController
        @RequestMapping("/somewhere-else")
        static class Controller {

            @GetMapping
            String notOurs() {
                return "not under /api/";
            }
        }
    }
}
