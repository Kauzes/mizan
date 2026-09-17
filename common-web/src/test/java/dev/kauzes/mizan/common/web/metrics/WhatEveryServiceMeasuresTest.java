package dev.kauzes.mizan.common.web.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * Latency is published as a distribution, by every service, without any of them saying so.
 *
 * <p>Until this, a count, a sum and a maximum were all a service published about how long it took. The
 * tail this platform actually has — a p95 sixteen times its p50 under load — was therefore only ever
 * visible to k6, from outside, during a load run. A number that can only be seen during a test is a
 * number that regresses between tests.
 */
class WhatEveryServiceMeasuresTest {

    private final WhatEveryServiceMeasures metrics = new WhatEveryServiceMeasures();

    @Test
    void httpLatencyIsPublishedAsBuckets() {
        MockEnvironment environment = new MockEnvironment();

        metrics.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(
                        "management.metrics.distribution.percentiles-histogram.http.server.requests"))
                .as("buckets, so Prometheus can compute a quantile across instances")
                .isEqualTo("true");
    }

    @Test
    void theBoundariesBracketWhatTheLoadProfilesClaim() {
        MockEnvironment environment = new MockEnvironment();

        metrics.postProcessEnvironment(environment, new SpringApplication());

        String boundaries =
                environment.getProperty("management.metrics.distribution.slo.http.server.requests");
        assertThat(boundaries)
                .as("500ms is what creating a payment claims, 800ms what authorizing and capturing claim")
                .contains("500ms")
                .contains("800ms");
        assertThat(boundaries.split(","))
                .as("bounded on purpose: latency is worth counting, not worth counting sixty ways")
                .hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void nothingIsMeasuredBelowNoiseOrAboveATimeout() {
        MockEnvironment environment = new MockEnvironment();

        metrics.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(
                        "management.metrics.distribution.minimum-expected-value.http.server.requests"))
                .isEqualTo("5ms");
        assertThat(environment.getProperty(
                        "management.metrics.distribution.maximum-expected-value.http.server.requests"))
                .isEqualTo("10s");
    }

    @Test
    void aServiceWithAReasonToDifferStillWins() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(
                "management.metrics.distribution.slo.http.server.requests", "1s,2s");

        metrics.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.metrics.distribution.slo.http.server.requests"))
                .as("contributed at the lowest precedence, like everything else this platform defaults")
                .isEqualTo("1s,2s");
    }
}
