package dev.kauzes.mizan.gateway.auth;

import dev.kauzes.mizan.common.identity.Principal;
import java.util.List;

/**
 * Who the gateway decided the caller is. Everything downstream reads comes from here, and
 * therefore from a token or a signature, and never from what the caller sent.
 */
public record VerifiedCaller(
        String userId, String merchantId, List<String> roles, Principal principal) {

    public static VerifiedCaller user(String userId, String merchantId, List<String> roles) {
        return new VerifiedCaller(userId, merchantId, roles, Principal.USER);
    }

    public String rolesHeader() {
        return String.join(",", roles);
    }
}
