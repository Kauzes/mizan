package dev.kauzes.mizan.identity.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Who may add people, who may change what they do, and what may never be taken away. */
@SpringBootTest
class UserManagementTest extends MizanIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anOwnerAddsAUser() throws Exception {
        Account account = register();

        addUser(account, account.owner(), "ANALYST")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"))
                .andExpect(jsonPath("$.merchantId").value(account.merchantId.toString()));

        mockMvc.perform(get(users(account)).with(account.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void anAdminMayReadThePeopleButNotAddOne() throws Exception {
        Account account = register();
        RequestPostProcessor admin = account.as(Role.ADMIN);

        mockMvc.perform(get(users(account)).with(admin)).andExpect(status().isOk());

        addUser(account, admin, "VIEWER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("You may not do that."));
    }

    @Test
    void anAnalystMayNotEvenSeeWhoElseIsThere() throws Exception {
        Account account = register();

        mockMvc.perform(get(users(account)).with(account.as(Role.ANALYST)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(users(account)).with(account.as(Role.VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyRoleMayReadTheMerchantItActsFor() throws Exception {
        Account account = register();

        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/v1/merchants/" + account.merchantId).with(account.as(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void onlyAnOwnerChangesRoles() throws Exception {
        Account account = register();
        UUID added = idOf(addUser(account, account.owner(), "VIEWER"));

        mockMvc.perform(roleChange(account, added, "ANALYST").with(account.as(Role.ADMIN)))
                .andExpect(status().isForbidden());

        mockMvc.perform(roleChange(account, added, "ANALYST").with(account.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"));
    }

    @Test
    void theLastOwnerCannotBeRemoved() throws Exception {
        Account account = register();

        mockMvc.perform(delete(users(account) + "/" + account.ownerId).with(account.owner()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.detail")
                        .value("A merchant must always have an owner. Make somebody else an "
                                + "owner first."));
    }

    @Test
    void theLastOwnerCannotBeDemoted() throws Exception {
        Account account = register();

        mockMvc.perform(roleChange(account, account.ownerId, "ADMIN").with(account.owner()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test
    void anOwnerMayStepDownOnceThereIsAnother() throws Exception {
        Account account = register();
        UUID second = idOf(addUser(account, account.owner(), "OWNER"));

        mockMvc.perform(roleChange(account, account.ownerId, "ADMIN").with(account.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));

        // And now that one is the last, so the same protection applies to them.
        mockMvc.perform(delete(users(account) + "/" + second).with(account.owner()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aRemovedUserIsGone() throws Exception {
        Account account = register();
        UUID added = idOf(addUser(account, account.owner(), "VIEWER"));

        mockMvc.perform(delete(users(account) + "/" + added).with(account.owner()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(users(account)).with(account.owner()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void asksForAtLeastOneRole() throws Exception {
        Account account = register();

        mockMvc.perform(post(users(account))
                        .with(account.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Nobody","roles":[]}
                                """.formatted(freshEmail(), PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private record Account(UUID merchantId, UUID ownerId) {

        RequestPostProcessor owner() {
            return Callers.owner(ownerId, merchantId);
        }

        /** The same person, seen as though they held a different role. */
        RequestPostProcessor as(Role role) {
            return Callers.as(ownerId, merchantId, role);
        }
    }

    private Account register() throws Exception {
        JsonNode registered = JSON.readTree(bodyOf(mockMvc.perform(post("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantName":"Kauzes Coffee","email":"%s","password":"%s",\
                        "fullName":"Sam Kauzes"}
                        """.formatted(freshEmail(), PASSWORD)))));

        return new Account(
                UUID.fromString(registered.path("merchant").path("id").asString()),
                UUID.fromString(registered.path("owner").path("id").asString()));
    }

    private ResultActions addUser(Account account, RequestPostProcessor caller, String role)
            throws Exception {

        return mockMvc.perform(post(users(account))
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Alex Kauzes","roles":["%s"]}
                        """.formatted(freshEmail(), PASSWORD, role)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder roleChange(
            Account account, UUID userId, String role) {

        return put(users(account) + "/" + userId + "/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"" + role + "\"]}");
    }

    private static String users(Account account) {
        return "/api/v1/merchants/" + account.merchantId + "/users";
    }

    private static UUID idOf(ResultActions actions) throws Exception {
        return UUID.fromString(JSON.readTree(bodyOf(actions)).path("id").asString());
    }

    private static String freshEmail() {
        return "person-" + UUID.randomUUID() + "@kauzes.dev";
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
