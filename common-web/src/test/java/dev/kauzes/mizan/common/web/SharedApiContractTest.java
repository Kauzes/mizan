package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kauzes.mizan.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The half of the API contract this module contributes to every service. It is asserted here
 * rather than only in the services because it arrives by auto configuration, and a condition
 * that stops matching would otherwise take the shared error contract out of every spec at
 * once without failing anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyErrorCodeCanBeReferencedAsAResponse() throws Exception {
        JsonNode responses = specification().path("components").path("responses");

        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name))
                .allSatisfy(code -> assertThat(responses.has(code))
                        .as("no response component for %s", code)
                        .isTrue());
    }

    @Test
    void theProblemSchemaCarriesWhatACallerBranchesOn() throws Exception {
        JsonNode problem = specification().path("components").path("schemas").path("Problem");

        assertThat(problem.path("required").valueStream().map(JsonNode::asText))
                .contains("code", "correlationId", "status");
        assertThat(problem.path("properties").path("errors").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/FieldViolation");
        assertThat(problem.path("example").path("code").asText())
                .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    }

    @Test
    void authenticationIsDescribedRatherThanLeftToBeGuessed() throws Exception {
        JsonNode schemes = specification().path("components").path("securitySchemes");

        assertThat(schemes.path("merchantJwt").path("scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.path("merchantApiKey").path("name").asText()).isEqualTo("X-Mizan-Key");
        assertThat(schemes.path("merchantSignature").path("in").asText()).isEqualTo("header");
    }

    @Test
    void theCorrelationHeaderIsDocumentedOnceAndReferenced() throws Exception {
        JsonNode components = specification().path("components");

        assertThat(components.path("headers").has("X-Correlation-Id")).isTrue();
        assertThat(components
                        .path("responses")
                        .path(ErrorCode.NOT_FOUND.name())
                        .path("headers")
                        .path("X-Correlation-Id")
                        .path("$ref")
                        .asText())
                .isEqualTo("#/components/headers/X-Correlation-Id");
    }

    @Test
    void theServiceStillDocumentsItsOwnEndpoints() throws Exception {
        assertThat(specification().path("paths").has("/test/validate"))
                .as("the shared contract should add to a service's operations, not replace them")
                .isTrue();
    }

    private JsonNode specification() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }
}
