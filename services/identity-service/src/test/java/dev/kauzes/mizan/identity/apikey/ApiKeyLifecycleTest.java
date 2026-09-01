package dev.kauzes.mizan.identity.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.RequestSigning;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The credential a merchant's server integrates with: issuing it, using it, and taking it
 * away. The signing here is done the way a merchant's client would do it, from
 * {@link RequestSigning} alone, so these tests double as proof that the published definition
 * is enough to write a client from.
 */
@SpringBootTest
class ApiKeyLifecycleTest extends MizanIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void showsTheSecretOnceAndNeverAgain() throws Exception {
        Account account = register();
        JsonNode issued = issue(account, "nightly reconciliation", Role.ADMIN);

        String secret = issued.path("secret").asString();
        assertThat(secret).startsWith("mzs_");
        assertThat(issued.path("key").path("keyId").asString()).startsWith("mzk_");

        String listed = body(mockMvc.perform(get(keys(account)).with(account.owner())));
        assertThat(listed)
                .as("a listing hands back no secret, because nothing can")
                .doesNotContain(secret)
                .doesNotContain("secret");
    }

    @Test
    void storesSomethingOtherThanTheSecret() throws Exception {
        Account account = register();
        JsonNode issued = issue(account, "billing worker", Role.ADMIN);
        String secret = issued.path("secret").asString();

        String stored = jdbc.queryForObject(
                "select secret_encrypted from api_key where key_id = ?",
                String.class,
                issued.path("key").path("keyId").asString());

        assertThat(stored).isNotNull().isNotEqualTo(secret).doesNotContain(secret);
    }

    @Test
    void acceptsARequestSignedWithTheKey() throws Exception {
        Account account = register();
        Key key = issued(account);

        verify(key, "POST", "/api/v1/payments", "{\"amount\":1200}", Instant.now())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(account.merchantId.toString()))
                .andExpect(jsonPath("$.keyId").value(key.keyId))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void refusesABodyThatDoesNotMatchTheSignature() throws Exception {
        Account account = register();
        Key key = issued(account);
        long now = Instant.now().getEpochSecond();

        String signature = key.sign("POST", "/api/v1/payments", "{\"amount\":1200}", now);

        mockMvc.perform(verification(
                        key.keyId,
                        signature,
                        "POST",
                        "/api/v1/payments",
                        now,
                        hash("{\"amount\":120000}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void refusesASignatureFromOutsideTheWindow() throws Exception {
        Account account = register();
        Key key = issued(account);

        verify(key, "GET", "/api/v1/payments", "", Instant.now().minus(Duration.ofMinutes(10)))
                .andExpect(status().isUnauthorized());

        // A clock ahead of ours is no better than one behind.
        verify(key, "GET", "/api/v1/payments", "", Instant.now().plus(Duration.ofMinutes(10)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAKeyNobodyIssued() throws Exception {
        Account account = register();
        Key key = issued(account);
        long now = Instant.now().getEpochSecond();

        mockMvc.perform(verification(
                        "mzk_nothing-was-ever-issued",
                        key.sign("GET", "/api/v1/payments", "", now),
                        "GET",
                        "/api/v1/payments",
                        now,
                        hash("")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedKeyStopsWorkingAtOnce() throws Exception {
        Account account = register();
        Key key = issued(account);

        verify(key, "GET", "/api/v1/payments", "", Instant.now()).andExpect(status().isOk());

        mockMvc.perform(delete(keys(account) + "/" + key.id).with(account.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        // No sleeping, no waiting for a cache: the next request is already refused.
        verify(key, "GET", "/api/v1/payments", "", Instant.now())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rotatingReplacesTheKeyAndRetiresTheOldOne() throws Exception {
        Account account = register();
        Key original = issued(account);

        JsonNode rotated = JSON.readTree(body(
                mockMvc.perform(post(keys(account) + "/" + original.id + "/rotate")
                                .with(account.owner()))
                        .andExpect(status().isCreated())));

        Key replacement = new Key(
                UUID.fromString(rotated.path("key").path("id").asString()),
                rotated.path("key").path("keyId").asString(),
                rotated.path("secret").asString());

        assertThat(replacement.keyId).isNotEqualTo(original.keyId);
        assertThat(rotated.path("key").path("name").asString())
                .as("a rotation keeps what the key was for")
                .isEqualTo("nightly reconciliation");
        assertThat(rotated.path("key").path("rotatedFrom").asString())
                .as("a rotation is a chain, not an unrelated pair")
                .isEqualTo(original.id.toString());

        verify(original, "GET", "/api/v1/payments", "", Instant.now())
                .andExpect(status().isUnauthorized());
        verify(replacement, "GET", "/api/v1/payments", "", Instant.now())
                .andExpect(status().isOk());
    }

    @Test
    void refusesASecretMovedOntoAnotherKeysRow() throws Exception {
        Account mine = register();
        Account theirs = register();
        Key known = issued(mine);
        Key target = issued(theirs);

        // Somebody able to write to this table copies the encrypted secret from a key they
        // hold onto another merchant's key. Both values were encrypted under the same key,
        // so nothing but the binding stops this one from opening.
        jdbc.update(
                "update api_key set secret_encrypted = "
                        + "(select secret_encrypted from api_key where key_id = ?) "
                        + "where key_id = ?",
                known.keyId,
                target.keyId);

        Key forged = new Key(target.id, target.keyId, known.secret);
        verify(forged, "GET", "/api/v1/payments", "", Instant.now())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAnOwnerManagesKeys() throws Exception {
        Account account = register();

        mockMvc.perform(get(keys(account)).with(account.as(Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(keys(account))
                        .with(account.as(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sneaky\",\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotTouchAnotherMerchantsKeys() throws Exception {
        Account mine = register();
        Account theirs = register();
        Key theirKey = issued(theirs);

        mockMvc.perform(get(keys(theirs)).with(mine.owner())).andExpect(status().isForbidden());
        mockMvc.perform(delete(keys(theirs) + "/" + theirKey.id).with(mine.owner()))
                .andExpect(status().isForbidden());

        // Their key id, through my own merchant's path: scoped lookup, so it is not found
        // rather than found and hidden.
        mockMvc.perform(delete(keys(mine) + "/" + theirKey.id).with(mine.owner()))
                .andExpect(status().isNotFound());

        verify(theirKey, "GET", "/api/v1/payments", "", Instant.now())
                .andExpect(status().isOk());
    }

    private record Account(UUID merchantId, UUID ownerId) {

        RequestPostProcessor owner() {
            return Callers.owner(ownerId, merchantId);
        }

        RequestPostProcessor as(Role role) {
            return Callers.as(ownerId, merchantId, role);
        }
    }

    private record Key(UUID id, String keyId, String secret) {

        String sign(String method, String path, String body, long timestamp) {
            return RequestSigning.sign(
                    secret,
                    RequestSigning.canonicalRequest(method, path, timestamp, hash(body)));
        }
    }

    private ResultActions verify(Key key, String method, String path, String body, Instant at)
            throws Exception {

        long timestamp = at.getEpochSecond();
        return mockMvc.perform(verification(
                key.keyId, key.sign(method, path, body, timestamp), method, path, timestamp,
                hash(body)));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            verification(
                    String keyId,
                    String signature,
                    String method,
                    String path,
                    long timestamp,
                    String bodyHash) {

        return post("/internal/api-keys/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"keyId":"%s","signature":"%s","method":"%s","path":"%s",\
                        "timestamp":%d,"bodyHash":"%s"}
                        """.formatted(keyId, signature, method, path, timestamp, bodyHash));
    }

    private Key issued(Account account) throws Exception {
        JsonNode issued = issue(account, "nightly reconciliation", Role.ADMIN);
        return new Key(
                UUID.fromString(issued.path("key").path("id").asString()),
                issued.path("key").path("keyId").asString(),
                issued.path("secret").asString());
    }

    private JsonNode issue(Account account, String name, Role role) throws Exception {
        return JSON.readTree(body(mockMvc.perform(post(keys(account))
                        .with(account.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"role\":\"%s\"}".formatted(name, role)))
                .andExpect(status().isCreated())));
    }

    private Account register() throws Exception {
        JsonNode registered = JSON.readTree(body(mockMvc.perform(post("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantName":"Kauzes Coffee","email":"owner-%s@kauzes.dev",\
                        "password":"%s","fullName":"Sam Kauzes"}
                        """.formatted(UUID.randomUUID(), PASSWORD)))));

        return new Account(
                UUID.fromString(registered.path("merchant").path("id").asString()),
                UUID.fromString(registered.path("owner").path("id").asString()));
    }

    private static String keys(Account account) {
        return "/api/v1/merchants/" + account.merchantId + "/api-keys";
    }

    private static String hash(String body) {
        return RequestSigning.bodyHash(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
