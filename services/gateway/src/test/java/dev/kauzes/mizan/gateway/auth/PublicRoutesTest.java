package dev.kauzes.mizan.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/**
 * The list of things anyone can reach. Worth its own test because every line of it is a
 * decision, and because the interesting cases are the ones that look public and are not.
 */
class PublicRoutesTest {

    private final PublicRoutes routes = new PublicRoutes();

    @Test
    void opensWhatCannotRequireAToken() {
        assertThat(isPublic(MockServerHttpRequest.post("/api/v1/tokens"))).isTrue();
        assertThat(isPublic(MockServerHttpRequest.post("/api/v1/tokens/refresh"))).isTrue();
        assertThat(isPublic(MockServerHttpRequest.post("/api/v1/merchants"))).isTrue();
        assertThat(isPublic(MockServerHttpRequest.get("/actuator/health"))).isTrue();
    }

    @Test
    void opensTheSpecsAndTheHealthOfEachService() {
        assertThat(isPublic(MockServerHttpRequest.get("/internal/identity-service/v3/api-docs")))
                .as("a published contract is documentation, not a secret")
                .isTrue();
        assertThat(isPublic(
                        MockServerHttpRequest.get("/internal/ledger-service/actuator/health")))
                .isTrue();
    }

    @Test
    void closesTheRestOfTheInternalScaffolding() {
        assertThat(isPublic(MockServerHttpRequest.get("/internal/identity-service/actuator")))
                .isFalse();
        assertThat(isPublic(MockServerHttpRequest.get("/internal/identity-service/actuator/info")))
                .isFalse();
        assertThat(isPublic(MockServerHttpRequest.get("/internal/identity-service/api/v1/merchants")))
                .as("the internal route must not become a way around the front door")
                .isFalse();
    }

    @Test
    void closesEverythingElseUnderTheApi() {
        assertThat(isPublic(MockServerHttpRequest.get("/api/v1/merchants/any-id"))).isFalse();
        assertThat(isPublic(MockServerHttpRequest.get("/api/v1/merchants"))).isFalse();
        assertThat(isPublic(MockServerHttpRequest.post("/api/v1/payments"))).isFalse();
    }

    @Test
    void opensARouteForOneMethodOnly() {
        assertThat(isPublic(MockServerHttpRequest.get("/api/v1/tokens")))
                .as("registering is a POST; the same path for a GET is not open")
                .isFalse();
        assertThat(isPublic(MockServerHttpRequest.delete("/api/v1/merchants"))).isFalse();
    }

    @Test
    void isReadableAsAList() {
        assertThat(routes.describe())
                .as("what is open should be legible without reading the matcher")
                .contains("POST /api/v1/tokens", "GET /internal/*/v3/api-docs");
    }

    private boolean isPublic(MockServerHttpRequest.BaseBuilder<?> request) {
        return routes.isPublic(request.build());
    }
}
