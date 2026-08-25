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
                        .containsExactly("/api/v1/payments/**"));
    }

    @Test
    void healthRoutesStripTheirPrefix() {
        Map<String, RouteDefinition> byId = definitions();

        assertThat(byId).containsKey("payment-service-health");
        assertThat(byId.get("payment-service-health").getFilters())
                .singleElement()
                .satisfies(filter -> {
                    assertThat(filter.getName()).isEqualTo("StripPrefix");
                    assertThat(filter.getArgs().values()).containsExactly("2");
                });
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
