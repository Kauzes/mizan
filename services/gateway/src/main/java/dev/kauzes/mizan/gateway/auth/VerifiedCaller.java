package dev.kauzes.mizan.gateway.auth;

import java.util.List;

/**
 * Who the gateway decided the caller is. Everything downstream reads comes from here, and
 * therefore from the token, and never from what the caller sent.
 */
public record VerifiedCaller(String userId, String merchantId, List<String> roles) {

    public String rolesHeader() {
        return String.join(",", roles);
    }
}
