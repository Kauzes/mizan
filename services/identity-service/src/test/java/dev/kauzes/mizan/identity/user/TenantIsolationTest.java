package dev.kauzes.mizan.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The boundary every later epic inherits.
 *
 * <p>Two merchants that really exist, both with real data, and one trying to reach the
 * other's. Proving this with a merchant id that was never created would prove much less: a
 * missing row refuses itself, and the interesting failure is the one where the row is there
 * and the query simply forgot whose it was.
 *
 * <p>These tests are meant to outlive this story. Every epic after this one adds tables that
 * have to respect the same boundary, and this is the shape of the test that shows they do.
 */
@SpringBootTest
class TenantIsolationTest extends MizanIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cannotReadAnotherMerchantThatReallyExists() throws Exception {
        Merchant mine = registerAMerchant("Kauzes Coffee");
        Merchant theirs = registerAMerchant("Rival Roasters");

        mockMvc.perform(get("/api/v1/merchants/" + theirs.id).with(mine.owner()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // The same merchant, read by somebody who is allowed to: the refusal above is about
        // who asked, not about the merchant being unreadable.
        mockMvc.perform(get("/api/v1/merchants/" + theirs.id).with(theirs.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rival Roasters"));
    }

    @Test
    void cannotListAnotherMerchantsPeople() throws Exception {
        Merchant mine = registerAMerchant("Kauzes Coffee");
        Merchant theirs = registerAMerchant("Rival Roasters");

        mockMvc.perform(get("/api/v1/merchants/" + theirs.id + "/users").with(mine.owner()))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotAddAUserToAnotherMerchant() throws Exception {
        Merchant mine = registerAMerchant("Kauzes Coffee");
        Merchant theirs = registerAMerchant("Rival Roasters");

        mockMvc.perform(post("/api/v1/merchants/" + theirs.id + "/users")
                        .with(mine.owner())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Intruder",\
                                "roles":["OWNER"]}
                                """.formatted(freshEmail(), PASSWORD)))
                .andExpect(status().isForbidden());

        assertThat(bodyOf(mockMvc.perform(
                        get("/api/v1/merchants/" + theirs.id + "/users").with(theirs.owner()))))
                .as("nobody should have been added")
                .doesNotContain("Intruder");
    }

    @Test
    void cannotTouchAUserOfAnotherMerchantEvenKnowingTheirId() throws Exception {
        Merchant mine = registerAMerchant("Kauzes Coffee");
        Merchant theirs = registerAMerchant("Rival Roasters");

        // Their user id, reached through their own merchant's path: the caller is refused for
        // being the wrong tenant, not for naming an id that does not exist.
        mockMvc.perform(put("/api/v1/merchants/" + theirs.id + "/users/" + theirs.ownerId
                                + "/roles")
                        .with(mine.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/merchants/" + theirs.id + "/users/" + theirs.ownerId)
                        .with(mine.owner()))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotSmuggleAnotherMerchantsUserThroughItsOwnPath() throws Exception {
        Merchant mine = registerAMerchant("Kauzes Coffee");
        Merchant theirs = registerAMerchant("Rival Roasters");

        // The path names the caller's own merchant, so the tenant check passes. The user id
        // belongs to somebody else, and the lookup is scoped to the merchant, so from here
        // that user simply does not exist.
        mockMvc.perform(delete("/api/v1/merchants/" + mine.id + "/users/" + theirs.ownerId)
                        .with(mine.owner()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/merchants/" + theirs.id + "/users").with(theirs.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(theirs.ownerId.toString()));
    }

    @Test
    void refusesAMerchantIdThatIsNotAnIdAtAll() throws Exception {
        Merchant mine = registerAMerchant("Kauzes Coffee");

        mockMvc.perform(get("/api/v1/merchants/not-a-uuid").with(mine.owner()))
                .andExpect(status().isForbidden());
    }

    private record Merchant(UUID id, UUID ownerId) {

        org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
            return Callers.owner(ownerId, id);
        }
    }

    private Merchant registerAMerchant(String name) throws Exception {
        JsonNode registered = bodyAsJson(mockMvc.perform(post("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantName":"%s","email":"%s","password":"%s","fullName":"Sam Kauzes"}
                        """.formatted(name, freshEmail(), PASSWORD))));

        return new Merchant(
                UUID.fromString(registered.path("merchant").path("id").asString()),
                UUID.fromString(registered.path("owner").path("id").asString()));
    }

    private static String freshEmail() {
        return "owner-" + UUID.randomUUID() + "@kauzes.dev";
    }

    private static JsonNode bodyAsJson(ResultActions actions) throws Exception {
        return JSON.readTree(bodyOf(actions));
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
