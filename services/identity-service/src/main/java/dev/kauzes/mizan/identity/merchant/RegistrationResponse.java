package dev.kauzes.mizan.identity.merchant;

import dev.kauzes.mizan.identity.user.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/** Both halves of what registration created, since neither is useful without the other. */
@Schema(description = "A newly registered merchant and its owner")
public record RegistrationResponse(MerchantResponse merchant, UserResponse owner) {
}
