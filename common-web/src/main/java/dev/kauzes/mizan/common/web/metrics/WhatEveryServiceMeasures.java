package dev.kauzes.mizan.common.web.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Latency, as a distribution rather than as an average and a maximum.
 *
 * <p>Every service already published `http_server_requests_seconds_count`, `_sum` and `_max`. Those
 * answer "how many" and "the worst one", and between them they cannot answer the question anybody
 * actually asks about latency: what does the slow end look like. A mean hides a tail by construction —
 * one request in twenty taking two seconds moves a mean by a tenth of a second — and a maximum is one
 * request, which is as likely to be a container starting as a problem.
 *
 * <p>That gap was found looking for the reason this platform's p95 is sixteen times its p50 under load
 * (docs/performance). The tail was measured by k6, from outside, during a load run. The platform itself
 * could not see it, which meant it could only ever be looked at during a test rather than noticed on an
 * ordinary day — and a number nobody can see between load runs is a number that regresses quietly.
 *
 * <p><b>Buckets, not client-side percentiles.</b> Micrometer can publish precomputed quantiles per
 * service, but they cannot be added up: the p95 of four instances is not the mean of their p95s, and a
 * dashboard that adds them is confidently wrong. Buckets can be summed, so Prometheus computes the
 * quantile across instances with `histogram_quantile`.
 *
 * <p><b>Bounded on purpose.</b> Seven boundaries, chosen to bracket what this platform claims — the
 * load profiles hold p95 under 500ms for creating and 800ms for authorizing and capturing (ADR 0054) —
 * rather than the default exponential set, which is dozens of series per endpoint per instance. Latency
 * is worth counting; it is not worth counting sixty ways.
 */
public class WhatEveryServiceMeasures implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE = "mizan-metrics-defaults";

    /** Around what the profiles claim, and far enough past it to show what breaks the claim. */
    private static final String BOUNDARIES = "50ms,100ms,250ms,500ms,800ms,2s,5s";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");
        defaults.put("management.metrics.distribution.slo.http.server.requests", BOUNDARIES);
        // Nothing this platform does is usefully measured below five milliseconds or above ten
        // seconds: below is noise, and above it the request has already failed a timeout somewhere.
        defaults.put("management.metrics.distribution.minimum-expected-value.http.server.requests", "5ms");
        defaults.put("management.metrics.distribution.maximum-expected-value.http.server.requests", "10s");
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
