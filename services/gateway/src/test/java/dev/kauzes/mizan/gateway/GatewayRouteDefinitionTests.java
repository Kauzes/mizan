package dev.kauzes.mizan.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

/**
 * Route configuration binds by property prefix, so a wrong prefix leaves the gateway
 * running with no routes at all and a context load test still passes. This asserts the
 * definitions actually arrived.
 */
@SpringBootTest
class GatewayRouteDefinitionTests {

    @Autowired
    private RouteDefinitionLocator routeDefinitions;

    @Test
    void everyDownstreamServiceHasARoute() {
        Map<String, RouteDefinition> byId = definitions();

        assertThat(byId).containsKeys(
                "identity-service",
                "ledger-service",
                "payment-service",
                "risk-service",
                "notification-service");

        assertThat(byId.get("payment-service").getUri())
                .hasToString("http://payment-service:8083");
        assertThat(byId.get("payment-service").getPredicates())
                .singleElement()
                .satisfies(predicate -> assertThat(predicate.getArgs().values())
                        .containsExactly(
                                "/api/v1/merchants/*/payments",
                                "/api/v1/merchants/*/payments/**"));
    }

    @Test
    void identityResourcesAreRoutedWithoutSayingSoInTheUrl() {
        Map<String, RouteDefinition> byId = definitions();

        assertThat(byId.get("identity-service").getPredicates())
                .singleElement()
                .satisfies(predicate -> assertThat(predicate.getArgs().values())
                        .containsExactly(
                                "/api/v1/merchants",
                                "/api/v1/merchants/**",
                                "/api/v1/tokens",
                                "/api/v1/tokens/**"));

        assertThat(byId.values())
                .as("a caller should never have to name a service to reach a resource")
                .noneSatisfy(route -> assertThat(route.getPredicates())
                        .anySatisfy(predicate -> assertThat(predicate.getArgs().values())
                                .contains("/api/v1/identity/**")));
    }

    @Test
    void aMerchantsBooksAreRoutedAheadOfTheMerchantItself() {
        Map<String, RouteDefinition> byId = definitions();

        assertThat(byId.get("ledger-service").getPredicates())
                .singleElement()
                .satisfies(predicate -> assertThat(predicate.getArgs().values())
                        .containsExactly(
                                "/api/v1/merchants/*/accounts",
                                "/api/v1/merchants/*/accounts/**",
                                "/api/v1/merchants/*/entries",
                                "/api/v1/merchants/*/entries/**"));

        // Both routes match a path like /api/v1/merchants/{id}/accounts, so the one that
        // should win says so with an order rather than by being read first.
        assertThat(byId.get("ledger-service").getOrder())
                .as("the more specific route has to be tried first")
                .isLessThan(byId.get("identity-service").getOrder());
        assertThat(byId.get("payment-service").getOrder())
                .as("and the same for a merchant's payments")
                .isLessThan(byId.get("identity-service").getOrder());
    }

    @Test
    void internalRoutesStripTheirPrefix() {
        Map<String, RouteDefinition> byId = definitions();

        assertThat(byId).containsKey("payment-service-internal");
        assertThat(byId.get("payment-service-internal").getFilters())
                .singleElement()
                .satisfies(filter -> {
                    assertThat(filter.getName()).isEqualTo("StripPrefix");
                    assertThat(filter.getArgs().values()).containsExactly("2");
                });
    }

    @Test
    void theInternalRoutesForwardOnlyDocumentationAndHealth() {
        // They were /internal/<service>/**, which forwarded every internal endpoint any
        // service would ever add. MIZ-45 wrote the first one that can move money between a
        // merchant's books and the platform's; a blanket route would have published it the
        // day it was written.
        java.util.List<String> forwarded = definitions().values().stream()
                .filter(route -> route.getId().endsWith("-internal"))
                .flatMap(route -> route.getPredicates().stream())
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .flatMap(paths -> java.util.Arrays.stream(paths.split(",")))
                .map(path -> path.endsWith("/**") ? path.substring(0, path.length() - 3) : path)
                .toList();

        assertThat(forwarded).isNotEmpty();
        assertThat(forwarded)
                .as("an internal route forwards a published contract and a health probe, and "
                        + "nothing else, because nothing else under /internal is the edge's to "
                        + "hand out")
                .allSatisfy(path -> assertThat(
                                path.endsWith("/v3/api-docs") || path.endsWith("/actuator/health"))
                        .as("%s", path)
                        .isTrue());
    }

    @Test
    void theAcquirerIsNotReachableFromOutside() {
        assertThat(definitions().values())
                .noneSatisfy(route -> assertThat(route.getUri().toString())
                        .contains("bank-simulator"));
    }

    private Map<String, RouteDefinition> definitions() {
        List<RouteDefinition> all = routeDefinitions.getRouteDefinitions().collectList().block();
        assertThat(all).isNotNull();
        return all.stream().collect(
                java.util.stream.Collectors.toMap(RouteDefinition::getId, route -> route));
    }
}
