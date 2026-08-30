package dev.kauzes.mizan.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kauzes.mizan.test.OpenApiSpecTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The identity service against the API contract every documented service has to meet. */
@SpringBootTest
class IdentityOpenApiSpecTest extends OpenApiSpecTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsRegistrationAndWhatItCanRefuse() throws Exception {
        JsonNode register = specification().path("paths").path("/api/v1/merchants").path("post");

        assertThat(register.isMissingNode()).as("registration should be documented").isFalse();
        assertThat(register.path("responses").has("201")).isTrue();
        assertThat(register.path("responses").path("400").path("$ref").asText())
                .isEqualTo("#/components/responses/VALIDATION_FAILED");
        assertThat(register.path("responses").path("409").path("$ref").asText())
                .isEqualTo("#/components/responses/CONFLICT");
    }

    @Test
    void neverDescribesAPasswordComingBack() throws Exception {
        JsonNode schemas = specification().path("components").path("schemas");

        assertThat(schemas.path("UserResponse").path("properties").has("password")).isFalse();
        assertThat(schemas.path("UserResponse").path("properties").has("passwordHash")).isFalse();
        assertThat(schemas.path("RegisterMerchantRequest").path("properties").has("password"))
                .as("it is still asked for, just never returned")
                .isTrue();
    }

    @Test
    void documentsSigningInAndRefreshing() throws Exception {
        JsonNode paths = specification().path("paths");

        assertThat(paths.path("/api/v1/tokens").path("post").path("responses").path("401")
                        .path("$ref").asText())
                .isEqualTo("#/components/responses/UNAUTHORIZED");
        assertThat(paths.path("/api/v1/tokens/refresh").path("post").isMissingNode())
                .as("a client cannot stay signed in against an undocumented endpoint")
                .isFalse();
        assertThat(paths.path("/.well-known/jwks.json").path("get").isMissingNode())
                .as("a verifier has to be able to find the key")
                .isFalse();
    }

    private JsonNode specification() throws Exception {
        String json =
                mockMvc.perform(get("/v3/api-docs"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(json);
    }
}
