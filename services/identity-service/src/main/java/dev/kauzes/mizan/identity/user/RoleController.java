package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What each role may do, said once, by the thing that decides it.
 *
 * <p>Written for the console. A token carries roles, and a console showing a person only the
 * things they can actually use has to turn those into permissions somehow. The alternative to
 * this endpoint is a copy of {@link Role}'s table in TypeScript, which would be correct on the
 * day it was written and wrong on the day somebody adds a permission — and wrong in the worst
 * direction, offering an action that will be refused.
 *
 * <p>Public because it is not a secret. It is the same table the API documentation prints, it
 * is identical for every merchant, and it says nothing about anybody: knowing that an analyst
 * may rule on reviews tells you nothing about who is an analyst.
 */
@RestController
@RequestMapping(path = "/api/v1/roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Roles", description = "What a role means, for anything that has to show it")
public class RoleController {

    @GetMapping
    @PublicEndpoint(
            because = "it is the platform's own access model rather than anybody's data. The "
                    + "same table the API documentation prints, identical for every merchant, "
                    + "and needed by a console before it can decide what to show.")
    @Operation(
            summary = "Every role, and what it may do",
            description =
                    """
                    Generated from the enum the services enforce, so a client reading this \
                    cannot disagree with the platform about what a role means.""")
    @ApiResponse(responseCode = "200", description = "The roles and their permissions")
    public Map<String, Object> roles() {
        Map<String, List<String>> byRole = Arrays.stream(Role.values())
                .collect(Collectors.toMap(
                        Enum::name,
                        role -> role.permissions().stream()
                                .map(Enum::name)
                                .sorted()
                                .toList()));

        return Map.of(
                "roles", byRole,
                // Listed as well as used, so a client can tell "this permission does not exist"
                // from "this role does not hold it" without guessing.
                "permissions",
                Arrays.stream(Permission.values())
                        .map(Enum::name)
                        .sorted(Comparator.naturalOrder())
                        .toList());
    }
}
