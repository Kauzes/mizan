package dev.kauzes.mizan.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a merchant asks to be told, and the secret that proves it was us.
 *
 * <p>Two things here are worth more attention than the CRUD around them: that the secret is
 * shown exactly once and is unreadable in the database, and that this platform cannot be
 * talked into calling an address inside its own network.
 */
@SpringBootTest
class WebhookEndpointTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registersAnEndpointAndHandsBackTheSecretOnce() throws Exception {
        Merchant merchant = merchant();

        String body = bodyOf(register(merchant, """
                {"url":"https://example.com/hooks/mizan","description":"Orders",
                 "eventTypes":["payment.captured","payment.refunded"]}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.endpoint.url").value("https://example.com/hooks/mizan"))
                .andExpect(jsonPath("$.endpoint.enabled").value(true))
                .andExpect(jsonPath("$.endpoint.eventTypes.length()").value(2)));

        String secret = JSON.readTree(body).path("secret").asString();
        String id = JSON.readTree(body).path("endpoint").path("id").asString();
        assertThat(secret).startsWith("whsec_");

        // And never again. Reading the endpoint back has no field for it to appear in.
        mockMvc.perform(get(endpoints(merchant) + "/" + id).with(merchant.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.secretRotatedAt").isNotEmpty());
    }

    @Test
    void theSecretIsUnreadableInTheDatabase() throws Exception {
        Merchant merchant = merchant();
        String body = bodyOf(register(merchant, """
                {"url":"https://example.com/hooks/one","eventTypes":["payment.captured"]}
                """));
        String secret = JSON.readTree(body).path("secret").asString();

        // The row holds a ciphertext. Somebody who reads the database and nothing else cannot
        // sign a delivery, which is the whole reason it is encrypted rather than stored.
        String stored = jdbc.queryForObject(
                "select secret from webhook_endpoint where merchant_id = ?",
                String.class,
                merchant.id);

        assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
        assertThat(jdbc.queryForList("select * from webhook_endpoint where merchant_id = ?",
                        merchant.id)
                        .toString())
                .as("nowhere in the row, in any column")
                .doesNotContain(secret);
    }

    @Test
    void rotatingIssuesANewSecretAndForgetsTheOld() throws Exception {
        Merchant merchant = merchant();
        String first = bodyOf(register(merchant, """
                {"url":"https://example.com/hooks/rotate","eventTypes":["payment.captured"]}
                """));
        String id = JSON.readTree(first).path("endpoint").path("id").asString();
        String was = JSON.readTree(first).path("secret").asString();

        String second = bodyOf(mockMvc.perform(post(endpoints(merchant) + "/" + id + "/secret")
                        .with(merchant.writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty()));

        assertThat(JSON.readTree(second).path("secret").asString())
                .as("a new secret, not the old one handed back")
                .isNotEqualTo(was);
    }

    @Test
    void willNotBeTalkedIntoCallingItself() throws Exception {
        Merchant merchant = merchant();

        // The reason this check exists. A platform that fetches any URL it is given will make
        // requests inside its own network for whoever asks.
        register(merchant, """
                {"url":"https://127.0.0.1/hooks","eventTypes":["payment.captured"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString(
                                "not an address on the public internet")));

        register(merchant, """
                {"url":"https://169.254.169.254/latest/meta-data/",
                 "eventTypes":["payment.captured"]}
                """)
                .andExpect(status().isUnprocessableContent());

        assertThat(howManyEndpoints(merchant)).isZero();
    }

    @Test
    void willNotDeliverInClearText() throws Exception {
        Merchant merchant = merchant();

        register(merchant, """
                {"url":"http://example.com/hooks","eventTypes":["payment.captured"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("has to be https")));
    }

    @Test
    void refusesAnEventTypeThisPlatformDoesNotPublish() throws Exception {
        Merchant merchant = merchant();

        // A typo here would otherwise be an endpoint that silently receives nothing forever.
        register(merchant, """
                {"url":"https://example.com/hooks/typo","eventTypes":["payment.capture"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString(
                                "does not publish payment.capture")));
    }

    @Test
    void refusesAnEndpointThatWantsNothing() throws Exception {
        Merchant merchant = merchant();

        register(merchant, """
                {"url":"https://example.com/hooks/nothing","eventTypes":[]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("an endpoint that wants nothing would never be called"));
    }

    @Test
    void oneUrlIsRegisteredOnce() throws Exception {
        Merchant merchant = merchant();
        register(merchant, """
                {"url":"https://example.com/hooks/twice","eventTypes":["payment.captured"]}
                """)
                .andExpect(status().isCreated());

        // Two rows for one URL would deliver everything twice, which looks like a platform
        // bug from the receiving end.
        register(merchant, """
                {"url":"https://example.com/hooks/twice","eventTypes":["payment.voided"]}
                """)
                .andExpect(status().isConflict());
    }

    @Test
    void canBeDisabledAndResumedWithoutLosingIt() throws Exception {
        Merchant merchant = merchant();
        String id = idOf(register(merchant, """
                {"url":"https://example.com/hooks/pause","eventTypes":["payment.captured"]}
                """));

        change(merchant, id, "{\"enabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        change(merchant, id, "{\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void whatItWantsCanBeChanged() throws Exception {
        Merchant merchant = merchant();
        String id = idOf(register(merchant, """
                {"url":"https://example.com/hooks/change","eventTypes":["payment.captured"]}
                """));

        change(merchant, id, "{\"eventTypes\":[\"payment.declined\",\"payment.voided\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTypes.length()").value(2));

        change(merchant, id, "{\"eventTypes\":[\"payment.nonsense\"]}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void deletingTakesItAndItsSubscriptions() throws Exception {
        Merchant merchant = merchant();
        String id = idOf(register(merchant, """
                {"url":"https://example.com/hooks/gone","eventTypes":["payment.captured"]}
                """));

        mockMvc.perform(delete(endpoints(merchant) + "/" + id).with(merchant.writer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(endpoints(merchant) + "/" + id).with(merchant.reader()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                        "select count(*) from webhook_subscription where endpoint_id = ?",
                        Long.class,
                        UUID.fromString(id)))
                .as("its subscriptions went with it rather than being left behind")
                .isZero();
    }

    @Test
    void cannotSeeOrChangeAnotherMerchantsEndpoint() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();
        String id = idOf(register(theirs, """
                {"url":"https://example.com/hooks/theirs","eventTypes":["payment.captured"]}
                """));

        mockMvc.perform(get(endpoints(mine) + "/" + id).with(mine.reader()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(endpoints(mine) + "/" + id + "/secret").with(mine.writer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(endpoints(mine) + "/" + id).with(mine.writer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingIsNotManaging() throws Exception {
        Merchant merchant = merchant();
        String id = idOf(register(merchant, """
                {"url":"https://example.com/hooks/roles","eventTypes":["payment.captured"]}
                """));

        // Rotating breaks every receiver still holding the old secret, which is not something
        // somebody who only needed to look should be able to do.
        mockMvc.perform(post(endpoints(merchant) + "/" + id + "/secret").with(merchant.reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(endpoints(merchant) + "/" + id).with(merchant.reader()))
                .andExpect(status().isOk());
    }

    // -- helpers ---------------------------------------------------------------------------

    private ResultActions register(Merchant merchant, String body) throws Exception {
        return mockMvc.perform(post(endpoints(merchant))
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions change(Merchant merchant, String id, String body) throws Exception {
        return mockMvc.perform(patch(endpoints(merchant) + "/" + id)
                .with(merchant.writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long howManyEndpoints(Merchant merchant) {
        Long counted = jdbc.queryForObject(
                "select count(*) from webhook_endpoint where merchant_id = ?",
                Long.class,
                merchant.id);
        return counted == null ? 0 : counted;
    }

    private String idOf(ResultActions actions) throws Exception {
        return JSON.readTree(bodyOf(actions)).path("endpoint").path("id").asString();
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }

        RequestPostProcessor reader() {
            return Callers.as(userId, id, Role.VIEWER);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }

    private static String endpoints(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/webhook-endpoints";
    }
}
