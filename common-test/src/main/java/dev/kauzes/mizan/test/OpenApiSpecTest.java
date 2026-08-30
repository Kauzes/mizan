package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kauzes.mizan.common.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What every service publishing an API has to prove. A service adds a subclass carrying its
 * own {@code @SpringBootTest} and gets the whole contract.
 *
 * <p>The committed spec is compared against the one the running service generates, so a spec
 * cannot quietly go stale: changing an endpoint without exporting the spec fails the build.
 * Running with {@code -Dmizan.openapi.write=true}, which is what the exportOpenApi task does,
 * rewrites the committed file instead of comparing against it.
 */
public abstract class OpenApiSpecTest extends MizanIntegrationTest {

    /** Where a service's committed spec lives, relative to the checkout. */
    private static final String SPEC_DIRECTORY = "docs/api";

    private static final String WRITE = "mizan.openapi.write";
    private static final String JSON = "/v3/api-docs";
    private static final String YAML = "/v3/api-docs.yaml";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void theCommittedSpecMatchesTheOneTheServiceGenerates() throws Exception {
        String generated = fetch(YAML);
        Path committed = committedSpec();

        if (Boolean.getBoolean(WRITE)) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(committed)
                .as("no committed spec for %s. Run ./gradlew exportOpenApi", serviceName())
                .exists();
        assertThat(read(committed))
                .as("the committed spec for %s is out of date. Run ./gradlew exportOpenApi",
                        serviceName())
                .isEqualTo(generated);
    }

    @Test
    void documentsEveryErrorTheServiceCanReturn() throws Exception {
        JsonNode responses = specification().path("components").path("responses");

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(responses.has(code.name()))
                    .as("no documented response for %s", code.name())
                    .isTrue();
            assertThat(responses.path(code.name()).path("content").has("application/problem+json"))
                    .as("%s is not documented as a problem detail", code.name())
                    .isTrue();
        }
    }

    @Test
    void documentsTheProblemShapeCallersBranchOn() throws Exception {
        JsonNode problem = specification().path("components").path("schemas").path("Problem");

        assertThat(problem.path("properties").has("code")).isTrue();
        assertThat(problem.path("properties").has("correlationId")).isTrue();
        assertThat(problem.path("properties").has("timestamp")).isTrue();

        JsonNode codes = problem.path("properties").path("code").path("enum");
        assertThat(codes.isArray()).as("the code field should list the codes it can carry").isTrue();
        assertThat(codes.valueStream().map(JsonNode::asText).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    void documentsHowACallerAuthenticates() throws Exception {
        JsonNode schemes = specification().path("components").path("securitySchemes");

        assertThat(schemes.has("merchantJwt")).isTrue();
        assertThat(schemes.has("merchantApiKey")).isTrue();
        assertThat(schemes.path("merchantApiKey").path("in").asText()).isEqualTo("header");

        // A scheme the gateway actually checks should not still be described as an intention.
        assertThat(schemes.path("merchantJwt").path("description").asText())
                .doesNotContain("Not enforced");
    }

    @Test
    void servesSwaggerUiForReadingItInABrowser() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Swagger UI should be reachable locally")
                        .isEqualTo(200));
    }

    private JsonNode specification() throws Exception {
        return JSON_MAPPER.readTree(fetch(JSON));
    }

    private String fetch(String path) throws Exception {
        return normalised(mockMvc.perform(get(path))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return normalised(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Line endings are the checkout's business, not the contract's. */
    private static String normalised(String content) {
        return content.replace("\r\n", "\n");
    }

    private Path committedSpec() {
        return RepositoryRoot.path().resolve(SPEC_DIRECTORY).resolve(serviceName() + ".yaml");
    }

    private String serviceName() {
        return environment.getRequiredProperty("spring.application.name");
    }
}
