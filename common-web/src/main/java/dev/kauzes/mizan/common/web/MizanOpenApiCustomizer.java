package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * The part of the API contract that is the same in every service: the problem detail shape,
 * one response per error code, the correlation id header, and how a caller authenticates.
 *
 * <p>Writing these once means a merchant reads the same error contract whichever service they
 * are integrating against, and an operation documents a failure by naming the code it can
 * return rather than describing the shape again.
 */
public class MizanOpenApiCustomizer implements OpenApiCustomizer {

    static final String PROBLEM = "Problem";
    static final String FIELD_VIOLATION = "FieldViolation";
    static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM;
    private static final String VIOLATION_REF = "#/components/schemas/" + FIELD_VIOLATION;
    private static final String CORRELATION_HEADER_REF =
            "#/components/headers/" + CorrelationContext.HEADER;

    private static final String DESCRIPTION =
            """
            Part of the Mizan payments platform. Every service is reached through the gateway \
            under /api/v1.

            Errors are RFC 9457 problem details. The code field is the contract: it comes from \
            a closed set, never changes meaning, and the HTTP status is derived from it. Branch \
            on the code rather than on the status or the message. The responses under \
            components.responses are named after those codes.

            Every request carries a correlation id. Send one in X-Correlation-Id and it is \
            echoed back and printed on every log line the request touches; send none and one is \
            generated at the edge. Quote it when reporting a problem.""";

    private final String applicationName;
    private final String version;

    public MizanOpenApiCustomizer(String applicationName, String version) {
        this.applicationName = applicationName;
        this.version = version;
    }

    @Override
    public void customise(OpenAPI openApi) {
        openApi.info(new Info().title(applicationName).version(version).description(DESCRIPTION));

        openApi.servers(List.of(new Server()
                .url("http://localhost:8080")
                .description("The local gateway. Services are not addressed directly.")));

        Components components =
                openApi.getComponents() == null ? new Components() : openApi.getComponents();
        components.addSchemas(PROBLEM, problem());
        components.addSchemas(FIELD_VIOLATION, fieldViolation());
        components.addHeaders(CorrelationContext.HEADER, correlationHeader());
        errorResponses(components);
        authentication(components);
        openApi.components(components);
    }

    /** One response per error code, so an operation documents a failure by naming the code. */
    private static void errorResponses(Components components) {
        for (ErrorCode code : ErrorCode.values()) {
            String description = "%d %s. The body carries code %s."
                    .formatted(code.status(), code.slug(), code.name());

            components.addResponses(
                    code.name(),
                    new ApiResponse()
                            .description(description)
                            .content(new Content()
                                    .addMediaType(
                                            PROBLEM_MEDIA_TYPE,
                                            new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))))
                            .addHeaderObject(
                                    CorrelationContext.HEADER,
                                    new Header().$ref(CORRELATION_HEADER_REF)));
        }
    }

    private static Schema<?> problem() {
        Schema<Object> schema = objectSchema();
        schema.description("An RFC 9457 problem detail. The body of every failing request.");
        schema.addProperty(
                "type",
                new StringSchema()
                        .format("uri")
                        .description("Identifies the error. It is not required to resolve."));
        schema.addProperty("title", new StringSchema().description("The code in readable form."));
        schema.addProperty(
                "status",
                new Schema<Integer>()
                        .type("integer")
                        .format("int32")
                        .description("The HTTP status, derived from the code."));
        schema.addProperty(
                "detail",
                new StringSchema()
                        .description("What went wrong, when the error was raised deliberately. An "
                                + "unexpected failure carries a fixed message and no internals."));
        schema.addProperty(
                "instance",
                new StringSchema().format("uri").description("The path of the request that failed."));
        schema.addProperty(
                "code",
                new StringSchema()
                        ._enum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList())
                        .description("The stable machine readable error. Branch on this."));
        schema.addProperty(
                "correlationId",
                new StringSchema()
                        .description("The id this request was handled under, for finding it in "
                                + "the logs."));
        schema.addProperty(
                "timestamp",
                new StringSchema().format("date-time").description("When the error was produced."));
        schema.addProperty(
                "errors",
                new ArraySchema()
                        .items(new Schema<>().$ref(VIOLATION_REF))
                        .description("Present only on a validation failure, one entry per field."));
        schema.setRequired(List.of("type", "title", "status", "code", "correlationId", "timestamp"));
        schema.example(problemExample());
        return schema;
    }

    /** Insertion ordered, so a regenerated spec does not shuffle between runs. */
    private static Map<String, Object> problemExample() {
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("field", "amount.minorUnits");
        violation.put("message", "must be greater than 0");

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("type", ErrorCode.VALIDATION_FAILED.type());
        example.put("title", ErrorCode.VALIDATION_FAILED.slug());
        example.put("status", ErrorCode.VALIDATION_FAILED.status());
        example.put("detail", "The request failed validation.");
        example.put("instance", "/api/v1/payments");
        example.put("code", ErrorCode.VALIDATION_FAILED.name());
        example.put("correlationId", "9f1c4e2a7b3d4f10");
        example.put("timestamp", "2026-08-27T09:15:00Z");
        example.put("errors", List.of(violation));
        return example;
    }

    private static Schema<?> fieldViolation() {
        Schema<Object> schema = objectSchema();
        schema.description("One rejected field, in the shape a form can render beside the input.");
        schema.addProperty("field", new StringSchema().description("The field that was rejected."));
        schema.addProperty("message", new StringSchema().description("Why it was rejected."));
        schema.setRequired(List.of("field", "message"));
        return schema;
    }

    /**
     * An ObjectSchema casts whatever it is handed to a string, which turns a worked example
     * into the toString of a map. The plain schema keeps it as the object a caller would read.
     */
    private static Schema<Object> objectSchema() {
        return new Schema<Object>().type("object");
    }

    private static Header correlationHeader() {
        return new Header()
                .description("The correlation id this request was handled under. Echoed from the "
                        + "request when it sent one, generated at the edge otherwise.")
                .schema(new StringSchema());
    }

    /**
     * The signing scheme, written out so that a merchant can implement it from the spec and
     * nothing else. A worked example rather than prose, because the thing that goes wrong is
     * always the exact bytes.
     */
    private static final String SIGNATURE_DESCRIPTION =
            """
            HMAC-SHA256 of the canonical request under the key's secret, in lowercase hex.

            The canonical request is four lines joined with a newline: the uppercase method, \
            the path, the value of X-Mizan-Timestamp, and the SHA-256 of the body in lowercase \
            hex, which for a request with no body is the digest of the empty string.

                POST
                /api/v1/payments
                1788100000
                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

            Method and path are covered so a captured signature cannot be aimed at another \
            endpoint, the body so it cannot be replayed with different numbers in it, and the \
            timestamp so it cannot be replayed at all.""";

    /**
     * How a caller proves who they are.
     *
     * <p>The bearer token is real: the gateway verifies it on every route that is not on its
     * public list, and an operation that needs one says so by referencing the scheme.
     *
     * <p>The API key pair is enforced too, and is described here precisely enough to write a
     * client from: the exact string that is signed, in the order its lines appear.
     */
    private static void authentication(Components components) {
        components.addSecuritySchemes(
                "merchantJwt",
                new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("A short lived access token issued by identity-service, sent "
                                + "as an Authorization bearer header. Obtained from POST "
                                + "/api/v1/tokens and renewed at /api/v1/tokens/refresh. The "
                                + "gateway verifies it and passes the established identity on; a "
                                + "service never sees the token itself."));

        components.addSecuritySchemes(
                "merchantApiKey",
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Mizan-Key")
                        .description("The merchant's API key, naming which key signed the "
                                + "request. Public, and not a secret. Issued at "
                                + "POST /api/v1/merchants/{merchantId}/api-keys, which returns "
                                + "the signing secret once. Paired with merchantSignature and "
                                + "merchantTimestamp; a key on its own is not enough."));

        components.addSecuritySchemes(
                "merchantSignature",
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Mizan-Signature")
                        .description(SIGNATURE_DESCRIPTION));

        components.addSecuritySchemes(
                "merchantTimestamp",
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Mizan-Timestamp")
                        .description("Unix seconds at which the request was signed. A request "
                                + "whose timestamp is further from the platform's clock than "
                                + "the accepted window, five minutes by default, is refused "
                                + "whatever its signature says."));
    }
}
