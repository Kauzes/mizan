package dev.kauzes.mizan.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Signing in, staying signed in, and what happens when a refresh token turns up twice.
 */
@SpringBootTest
class TokenExchangeTest extends MizanIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exchangesCredentialsForAPair() throws Exception {
        String email = registerAMerchant();

        signIn(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshExpiresIn").value(2592000));
    }

    @Test
    void theAccessTokenVerifiesAgainstTheKeyTheServicePublishes() throws Exception {
        String email = registerAMerchant();
        JsonNode pair = bodyOf(signIn(email, PASSWORD));

        JWKSet published = JWKSet.parse(bodyOf(mockMvc.perform(get("/.well-known/jwks.json"))).toString());
        RSAKey publicKey = (RSAKey) published.getKeys().get(0);

        SignedJWT token = SignedJWT.parse(pair.path("accessToken").asText());

        assertThat(token.verify(new RSASSAVerifier(publicKey)))
                .as("anyone holding the published key can verify a token, with no lookup")
                .isTrue();
        assertThat(token.getHeader().getKeyID()).isEqualTo(publicKey.getKeyID());
        assertThat(token.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("OWNER");
        assertThat(token.getJWTClaimsSet().getStringClaim("merchant")).isNotEmpty();
        assertThat(token.getJWTClaimsSet().getSubject()).isNotEmpty();
    }

    @Test
    void publishesOnlyThePublicHalfOfTheKey() throws Exception {
        JsonNode jwks = bodyOf(mockMvc.perform(get("/.well-known/jwks.json")));
        JsonNode key = jwks.path("keys").get(0);

        assertThat(key.has("d")).as("a private exponent has no business being served").isFalse();
        assertThat(key.has("p")).isFalse();
        assertThat(key.has("q")).isFalse();
        assertThat(key.path("kty").asText()).isEqualTo("RSA");
    }

    @Test
    void refreshingRotatesBothTokens() throws Exception {
        JsonNode first = bodyOf(signIn(registerAMerchant(), PASSWORD));
        JsonNode second = bodyOf(refresh(first.path("refreshToken").asText())
                .andExpect(status().isOk()));

        assertThat(second.path("refreshToken").asText())
                .as("the refresh token is spent by using it")
                .isNotEqualTo(first.path("refreshToken").asText());
        assertThat(second.path("accessToken").asText())
                .isNotEqualTo(first.path("accessToken").asText());
    }

    @Test
    void aSpentRefreshTokenIsRefusedAndTakesItsFamilyWithIt() throws Exception {
        JsonNode first = bodyOf(signIn(registerAMerchant(), PASSWORD));
        String spent = first.path("refreshToken").asText();

        JsonNode second = bodyOf(refresh(spent).andExpect(status().isOk()));

        refresh(spent)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // The token the first refresh handed back was never presented twice, and is refused
        // anyway: replaying one token revokes every token descended from that sign in.
        refresh(second.path("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAWrongPasswordAndAnUnknownAddressAlike() throws Exception {
        String registered = registerAMerchant();

        JsonNode wrongPassword = bodyOf(
                signIn(registered, "the-wrong-password").andExpect(status().isUnauthorized()));
        JsonNode noSuchAccount = bodyOf(
                signIn("nobody-" + UUID.randomUUID() + "@kauzes.dev", PASSWORD)
                        .andExpect(status().isUnauthorized()));

        assertThat(comparable(noSuchAccount))
                .as("telling these apart would answer whether an address has an account")
                .isEqualTo(comparable(wrongPassword));
    }

    @Test
    void refusesARefreshTokenItNeverIssued() throws Exception {
        refresh("not-a-token-this-service-ever-handed-out")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void asksForCredentialsThatAreAtLeastPresent() throws Exception {
        mockMvc.perform(post("/api/v1/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** The parts of a refusal that should be identical, whatever was actually wrong. */
    private static JsonNode comparable(JsonNode problem) {
        ObjectNode copy = ((ObjectNode) problem).deepCopy();
        copy.remove("correlationId");
        copy.remove("timestamp");
        return copy;
    }

    private String registerAMerchant() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@kauzes.dev";
        mockMvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantName":"Kauzes Coffee","email":"%s",\
                                "password":"%s","fullName":"Sam Kauzes"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions signIn(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/tokens/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refreshToken)));
    }

    private static JsonNode bodyOf(ResultActions actions) throws Exception {
        return JSON.readTree(
                actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
