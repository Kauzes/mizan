package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * What happens when the same request arrives twice.
 *
 * <p>The ledger already had this on the reference an entry carries. A payment needs it on the
 * header, because a caller retrying is retrying an attempt to move somebody's money.
 */
@SpringBootTest
class IdempotencyTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aWriteWithoutAKeyIsRefused() throws Exception {
        Merchant merchant = merchant();

        mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("order-" + UUID.randomUUID(), 125000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        assertThat(paymentsOf(merchant))
                .as("and nothing was created while refusing it")
                .isZero();
    }

    @Test
    void theSameKeyTwiceCreatesOnePayment() throws Exception {
        Merchant merchant = merchant();
        String key = UUID.randomUUID().toString();
        String body = body("order-" + UUID.randomUUID(), 125000);

        JsonNode first = JSON.readTree(bodyOf(
                create(merchant, key, body).andExpect(status().isCreated())));
        JsonNode second = JSON.readTree(bodyOf(
                create(merchant, key, body).andExpect(status().isCreated())));

        assertThat(second)
                .as("a retry cannot be told from the call it is retrying")
                .isEqualTo(first);
        assertThat(paymentsOf(merchant)).isEqualTo(1L);
    }

    @Test
    void aReplayCarriesTheOriginalStatus() throws Exception {
        Merchant merchant = merchant();
        String key = UUID.randomUUID().toString();
        String body = body("order-" + UUID.randomUUID(), 125000);

        ResultActions first = create(merchant, key, body);
        ResultActions second = create(merchant, key, body);

        assertThat(second.andReturn().getResponse().getStatus())
                .as("a client should not have to handle a second shape of success")
                .isEqualTo(first.andReturn().getResponse().getStatus());
    }

    @Test
    void theSameKeyForADifferentRequestIsRefused() throws Exception {
        Merchant merchant = merchant();
        String key = UUID.randomUUID().toString();

        create(merchant, key, body("order-" + UUID.randomUUID(), 125000))
                .andExpect(status().isCreated());

        create(merchant, key, body("order-" + UUID.randomUUID(), 999999))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail")
                        .value("Idempotency-Key " + key + " was already used for a different "
                                + "request."));
        assertThat(paymentsOf(merchant)).isEqualTo(1L);
    }

    @Test
    void aKeyIsScopedToTheMerchant() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();
        String key = "the-same-key";
        String body = body("order-shared", 125000);

        create(mine, key, body).andExpect(status().isCreated());
        // Two merchants using one key is not a collision.
        create(theirs, key, body).andExpect(status().isCreated());

        assertThat(paymentsOf(mine)).isEqualTo(1L);
        assertThat(paymentsOf(theirs)).isEqualTo(1L);
    }

    @Test
    void aFailedRequestGivesTheKeyBack() throws Exception {
        Merchant merchant = merchant();
        String key = UUID.randomUUID().toString();

        // A payment for nothing is refused by validation, which is not an outcome worth
        // replaying: the caller should be able to fix the request and use the same key.
        create(merchant, key, body("order-" + UUID.randomUUID(), 0))
                .andExpect(status().isBadRequest());

        create(merchant, key, body("order-" + UUID.randomUUID(), 125000))
                .andExpect(status().isCreated());
        assertThat(paymentsOf(merchant)).isEqualTo(1L);
    }

    @Test
    void tenCallersRacingOneKeyCreateOnePayment() throws Exception {
        Merchant merchant = merchant();
        String key = UUID.randomUUID().toString();
        String body = body("order-" + UUID.randomUUID(), 125000);
        int callers = 10;

        CyclicBarrier together = new CyclicBarrier(callers);
        ExecutorService threads = Executors.newFixedThreadPool(callers);

        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = threads.invokeAll(Collections.nCopies(
                    callers,
                    (Callable<Integer>) () -> {
                        together.await();
                        return create(merchant, key, body).andReturn().getResponse().getStatus();
                    }));
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(paymentsOf(merchant))
                .as("however many asked, the payment is created once")
                .isEqualTo(1L);
        assertThat(statuses)
                .as("and nobody is told something that is not either the answer or wait")
                .allSatisfy(status -> assertThat(status).isIn(201, 409));
        assertThat(statuses).contains(201);
    }

    @Test
    void aReadNeedsNoKey() throws Exception {
        Merchant merchant = merchant();

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        payments(merchant))
                                .with(merchant.writer()))
                .andExpect(status().isOk());
    }

    private long paymentsOf(Merchant merchant) {
        Long counted = jdbc.queryForObject(
                "select count(*) from payment where merchant_id = ?", Long.class, merchant.id);
        return counted == null ? 0 : counted;
    }

    private ResultActions create(Merchant merchant, String key, String body) throws Exception {
        return mockMvc.perform(post(payments(merchant))
                .with(merchant.writer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(String reference, long amount) {
        return """
                {"amount":%d,"currency":"TRY","reference":"%s","description":"Two bags"}
                """.formatted(amount, reference);
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
