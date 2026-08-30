package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.identity.Role;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * Writes what an operation requires into the spec, from the annotation that enforces it.
 *
 * <p>Documenting the required role by hand is documenting it twice, and the copy that is not
 * enforced is the one that goes stale. Here the spec is generated from the same declaration
 * the interceptor reads, so an endpoint whose permission changes says so without anybody
 * remembering to edit a description.
 */
public class RequiredPermissionCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (handlerMethod.hasMethodAnnotation(PublicEndpoint.class)) {
            return operation;
        }

        RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            return operation;
        }

        operation.addSecurityItem(new SecurityRequirement().addList("merchantJwt"));
        operation.setDescription(withRequirement(operation.getDescription(), required));

        refusal(operation, ErrorCode.UNAUTHORIZED);
        refusal(operation, ErrorCode.FORBIDDEN);
        return operation;
    }

    private static String withRequirement(String existing, RequiresPermission required) {
        String holders = Arrays.stream(Role.values())
                .filter(role -> role.can(required.value()))
                .map(Enum::name)
                .collect(Collectors.joining(", "));

        String sentence = "Requires the "
                + required.value().name()
                + " permission, held by: "
                + holders
                + ". The caller must be acting for the merchant in the path.";

        return existing == null || existing.isBlank() ? sentence : existing + "\n\n" + sentence;
    }

    /** Every guarded operation can be refused these two ways, so it documents both. */
    private static void refusal(Operation operation, ErrorCode code) {
        String status = String.valueOf(code.status());
        if (operation.getResponses() != null && operation.getResponses().containsKey(status)) {
            return;
        }
        operation.getResponses()
                .addApiResponse(
                        status,
                        new ApiResponse().$ref("#/components/responses/" + code.name()));
    }
}
