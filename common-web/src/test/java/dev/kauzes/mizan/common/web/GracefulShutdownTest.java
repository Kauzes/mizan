package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.kauzes.mizan.common.web.lifecycle.HowThisPlatformStops;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A request in flight when the service is told to stop still gets its answer.
 *
 * <p>This is what a rolling deploy does to every pod, many times a day: the old pod is told to stop
 * while it is in the middle of somebody's authorization. With an immediate shutdown that request
 * dies with a reset connection, the caller cannot tell whether the money moved, and the platform
 * has manufactured an unknown outcome out of an ordinary deployment.
 *
 * <p>A real server on a real port, a real request, and the application closed while the request is
 * being handled. Then the same again with shutdown set to immediate, where the request must fail —
 * because a test that passes whichever way the platform is configured proves nothing about the
 * configuration.
 *
 * <p>In this package rather than beside HowThisPlatformStops, because the slow endpoint belongs to
 * the test application and is not something to make public for a test's convenience.
 */
class GracefulShutdownTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aRequestInFlightWhenTheServiceIsToldToStopStillGetsItsAnswer() throws Exception {
        ConfigurableApplicationContext service = start();

        CompletableFuture<HttpResponse<String>> inFlight = slowRequestTo(service);
        service.close();

        HttpResponse<String> answer = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body())
                .as("the request that was being handled finished, rather than being cut off")
                .isEqualTo("finished");
    }

    @Test
    void andWithoutGracefulShutdownTheSameRequestIsCutOff() throws Exception {
        // The control. If this passed as well, the test above would be passing for some reason
        // other than the shutdown configuration.
        ConfigurableApplicationContext service = start("--server.shutdown=immediate");

        CompletableFuture<HttpResponse<String>> inFlight = slowRequestTo(service);
        service.close();

        assertThat(catchThrowable(() -> inFlight.get(10, TimeUnit.SECONDS)))
                .as("an immediate shutdown should drop the request that was in flight")
                .isNotNull();
    }

    @Test
    void everyServiceStopsGracefullyAndAliveMeansOnlyTheProcess() {
        ConfigurableApplicationContext service = start();
        try {
            assertThat(service.getEnvironment().getProperty("server.shutdown"))
                    .isEqualTo("graceful");
            assertThat(service.getEnvironment()
                            .getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                    .isEqualTo(HowThisPlatformStops.SHUTDOWN_PHASE);
            assertThat(service.getEnvironment()
                            .getProperty("management.endpoint.health.group.liveness.include"))
                    .as("liveness must not depend on a database or a broker")
                    .isEqualTo("livenessState");
        } finally {
            service.close();
        }
    }

    private static ConfigurableApplicationContext start(String... extra) {
        String[] arguments = new String[extra.length + 2];
        arguments[0] = "--server.port=0";
        arguments[1] = "--spring.main.banner-mode=off";
        System.arraycopy(extra, 0, arguments, 2, extra.length);
        return new SpringApplicationBuilder(TestApplication.class).run(arguments);
    }

    private CompletableFuture<HttpResponse<String>> slowRequestTo(
            ConfigurableApplicationContext service) throws InterruptedException {

        CountDownLatch started = new CountDownLatch(1);
        TestApplication.TestController.slowStarted = started;

        String port = service.getEnvironment().getProperty("local.server.port");
        CompletableFuture<HttpResponse<String>> inFlight = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/slow")).build(),
                HttpResponse.BodyHandlers.ofString());

        // Closed only once the request is actually being handled, not after a guessed delay.
        assertThat(started.await(10, TimeUnit.SECONDS))
                .as("the slow request should have reached the controller")
                .isTrue();
        return inFlight;
    }
}
