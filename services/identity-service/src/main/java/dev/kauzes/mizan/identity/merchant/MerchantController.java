package dev.kauzes.mizan.identity.merchant;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.PublicEndpoint;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/merchants", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Merchants", description = "Opening an account and reading who acts for it")
public class MerchantController {

    private final MerchantRegistrationService registration;

    public MerchantController(MerchantRegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping
    @PublicEndpoint(because = "opening the first account cannot require an account")
    @Operation(
            summary = "Register a merchant",
            description =
                    "Creates the merchant and the owner account together. Either both exist "
                            + "afterwards or neither does.")
    @ApiResponse(responseCode = "201", description = "The merchant and its owner")
    @ApiResponse(
            responseCode = "400",
            ref = "#/components/responses/VALIDATION_FAILED",
            description = "A field was missing or malformed")
    @ApiResponse(
            responseCode = "409",
            ref = "#/components/responses/CONFLICT",
            description = "That email address already has an account")
    public ResponseEntity<RegistrationResponse> register(
            @Valid @RequestBody RegisterMerchantRequest request) {

        RegistrationResponse registered = registration.register(request);
        return ResponseEntity.created(
                        URI.create("/api/v1/merchants/" + registered.merchant().id()))
                .body(registered);
    }

    @GetMapping("/{merchantId}")
    @RequiresPermission(Permission.MERCHANT_READ)
    @Operation(summary = "Read a merchant")
    @ApiResponse(responseCode = "200", description = "The merchant")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "No merchant with that id")
    public MerchantResponse find(@PathVariable UUID merchantId) {
        return registration.find(merchantId);
    }
}
