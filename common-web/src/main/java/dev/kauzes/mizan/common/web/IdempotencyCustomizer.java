package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.error.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * Says in the spec what the annotation already says in the code: this write needs a key, and
 * repeating it with the same one is safe.
 *
 * <p>Written from the annotation rather than by hand, for the same reason the required
 * permission is: the copy nobody enforces is the copy that goes stale.
 */
public class IdempotencyCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (!handlerMethod.hasMethodAnnotation(Idempotent.class)) {
            return operation;
        }

        operation.addParametersItem(new Parameter()
                .in("header")
                .name(IdempotencyInterceptor.HEADER)
                .required(true)
                .schema(new StringSchema().maxLength(200))
                .description(
                        "Names this attempt, so that repeating it cannot repeat its effect. "
                                + "Sending the same key again returns what the first call "
                                + "produced, with the same status, so a retry after a lost "
                                + "response is safe. The same key with a different body is "
                                + "refused. A key belongs to one merchant and one operation."));

        refusal(operation, ErrorCode.CONFLICT, "That key was already used for a different request");
        refusal(
                operation,
                ErrorCode.CONTENDED,
                "A request with that key is still in flight. Send it again in a moment.");
        return operation;
    }

    private static void refusal(Operation operation, ErrorCode code, String description) {
        String status = String.valueOf(code.status());
        if (operation.getResponses() == null || operation.getResponses().containsKey(status)) {
            return;
        }
        operation.getResponses()
                .addApiResponse(
                        status,
                        new ApiResponse()
                                .$ref("#/components/responses/" + code.name())
                                .description(description));
    }
}
