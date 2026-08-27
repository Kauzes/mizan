package dev.kauzes.mizan.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

/**
 * The gateway is where the whole platform's API is browsed, and the list of specs it offers
 * is configuration that nothing else checks. A service routed here but missing from the list
 * is a service whose API is invisible, which is the kind of gap nobody notices until they go
 * looking for the endpoint.
 */
@SpringBootTest
class GatewayApiDocsAggregationTest {

    @Autowired
    private SwaggerUiConfigProperties swaggerUi;

    @Autowired
    private RouteDefinitionLocator routeDefinitions;

    @Test
    void everyServiceReachableThroughTheGatewayOffersItsSpec() {
        Set<String> documented = swaggerUi.getUrls().stream()
                .map(url -> url.getName())
                .collect(Collectors.toSet());

        assertThat(documented).containsExactlyInAnyOrderElementsOf(internallyRoutedServices());
    }

    @Test
    void eachSpecIsFetchedThroughThatServicesInternalRoute() {
        swaggerUi.getUrls()
                .forEach(url -> assertThat(url.getUrl())
                        .isEqualTo("/internal/" + url.getName() + "/v3/api-docs"));
    }

    @Test
    void theGatewayPublishesNoApiOfItsOwn() {
        assertThat(swaggerUi.getUrls())
                .as("the gateway routes; it has nothing to document")
                .noneSatisfy(url -> assertThat(url.getName()).isEqualTo("gateway"));
    }

    /** The services with an internal route, which is how a spec is reached. */
    private Set<String> internallyRoutedServices() {
        List<RouteDefinition> all = routeDefinitions.getRouteDefinitions().collectList().block();
        assertThat(all).isNotNull();
        return all.stream()
                .map(RouteDefinition::getId)
                .filter(id -> id.endsWith("-internal"))
                .map(id -> id.substring(0, id.length() - "-internal".length()))
                .collect(Collectors.toSet());
    }
}
