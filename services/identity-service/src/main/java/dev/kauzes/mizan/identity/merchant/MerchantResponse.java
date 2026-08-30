package dev.kauzes.mizan.identity.merchant;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "The account money belongs to")
public record MerchantResponse(UUID id, String name, Instant createdAt) {

    public static MerchantResponse of(Merchant merchant) {
        return new MerchantResponse(merchant.id(), merchant.name(), merchant.createdAt());
    }
}
