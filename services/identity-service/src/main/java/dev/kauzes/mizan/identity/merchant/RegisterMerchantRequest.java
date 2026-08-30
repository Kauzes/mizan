package dev.kauzes.mizan.identity.merchant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What opening an account asks for: the merchant, and the first person who can sign in to it.
 */
@Schema(description = "A new merchant and the owner account created with it")
public record RegisterMerchantRequest(
        @Schema(description = "Trading name of the merchant", example = "Kauzes Coffee")
                @NotBlank
                @Size(max = 200)
                String merchantName,
        @Schema(description = "Email the owner signs in with", example = "owner@kauzes.dev")
                @NotBlank
                @Email
                @Size(max = 320)
                String email,
        @Schema(
                        description =
                                "Owner's password. Stored as a salted hash and never returned.",
                        format = "password",
                        minLength = MIN_PASSWORD_LENGTH)
                @NotBlank
                @Size(
                        min = MIN_PASSWORD_LENGTH,
                        max = 200,
                        message = "must be at least " + MIN_PASSWORD_LENGTH + " characters")
                String password,
        @Schema(description = "Owner's name", example = "Sam Kauzes") @NotBlank @Size(max = 200)
                String fullName) {

    /**
     * Length is the only password rule worth enforcing here. Composition rules push people
     * towards predictable substitutions, and the real defences against guessing are the slow
     * hash and the throttling MIZ-13 adds.
     */
    static final int MIN_PASSWORD_LENGTH = 12;

    /** Whatever logs a request, it does not log the password. */
    @Override
    public String toString() {
        return "RegisterMerchantRequest[merchantName="
                + merchantName
                + ", email="
                + email
                + ", fullName="
                + fullName
                + ", password=***]";
    }
}
