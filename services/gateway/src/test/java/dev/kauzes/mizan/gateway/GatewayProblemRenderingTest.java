package dev.kauzes.mizan.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.web.Problems;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The gateway writes its own error bodies, because it refuses a request before any handler
 * runs and cannot go through the shared exception handler, which is a servlet one.
 *
 * <p>That leaves one way for the two to drift: the mapper. A problem detail whose extra
 * fields are nested under {@code properties} rather than flattened is a body a caller cannot
 * branch on the same way, and nothing else would notice. This holds the application's own
 * mapper to the shape the rest of the platform returns.
 */
@SpringBootTest
class GatewayProblemRenderingTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void rendersAProblemDetailFlat() {
        ProblemDetail problem = Problems.of(
                ErrorCode.UNAUTHORIZED, "The credentials are not valid.", "abc-123", List.of());

        JsonNode rendered = mapper.readTree(mapper.writeValueAsString(problem));

        assertThat(rendered.path("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(rendered.path("correlationId").asString()).isEqualTo("abc-123");
        assertThat(rendered.path("timestamp").asString()).isNotEmpty();
        assertThat(rendered.path("status").asInt()).isEqualTo(401);
        assertThat(rendered.path("title").asString()).isEqualTo("unauthorized");
        assertThat(rendered.path("detail").asString()).isEqualTo("The credentials are not valid.");
        assertThat(rendered.path("type").asString())
                .isEqualTo("https://mizan.kauzes.dev/errors/unauthorized");
        assertThat(rendered.has("properties"))
                .as("the platform's fields are top level, not nested")
                .isFalse();
    }
}
