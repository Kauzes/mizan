package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.Idempotent;
import dev.kauzes.mizan.common.web.NotIdempotent;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * The people acting for a merchant.
 *
 * <p>Every path here names the merchant, which is what lets the caller be checked against it
 * before a handler runs. Nothing in this controller takes a merchant id from a body.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/users",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Users", description = "Who acts for a merchant, and what they may do")
public class UserController {

    private final MerchantUserService users;

    public UserController(MerchantUserService users) {
        this.users = users;
    }

    @GetMapping
    @RequiresPermission(Permission.USER_READ)
    @Operation(
            summary = "List the users of a merchant",
            description = "Oldest first. No password or hash appears in this response.")
    @ApiResponse(responseCode = "200", description = "The merchant's users")
    public List<UserResponse> list(@PathVariable UUID merchantId) {
        return users.list(merchantId);
    }

    @PostMapping
    @RequiresPermission(Permission.USER_MANAGE)
    @Idempotent
    @Operation(summary = "Add a user to a merchant")
    @ApiResponse(responseCode = "201", description = "The user that was added")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "That email address already has an account")
    public ResponseEntity<UserResponse> add(
            @PathVariable UUID merchantId, @Valid @RequestBody AddUserRequest request) {

        UserResponse added = users.add(merchantId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + merchantId + "/users/" + added.id()))
                .body(added);
    }

    @PutMapping("/{userId}/roles")
    @RequiresPermission(Permission.ROLE_MANAGE)
    @NotIdempotent(
            because = "replacing a set of roles with the same set twice leaves the same "
                    + "roles, so a repeat changes nothing")
    @Operation(
            summary = "Replace a user's roles",
            description = "The set sent is the set the user holds afterwards.")
    @ApiResponse(responseCode = "200", description = "The user, with their new roles")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no user with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "That would leave the merchant with no owner")
    public UserResponse setRoles(
            @PathVariable UUID merchantId,
            @PathVariable UUID userId,
            @Valid @RequestBody SetRolesRequest request) {

        return users.setRoles(merchantId, userId, request.roles());
    }

    @DeleteMapping("/{userId}")
    @RequiresPermission(Permission.USER_MANAGE)
    @NotIdempotent(
            because = "removing a user who is already gone is refused as not found, "
                    + "which is the same answer a repeat should get")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a user from a merchant")
    @ApiResponse(responseCode = "204", description = "The user is gone")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This merchant has no user with that id")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "That would leave the merchant with no owner")
    public void remove(@PathVariable UUID merchantId, @PathVariable UUID userId) {
        users.remove(merchantId, userId);
    }
}
