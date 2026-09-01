package dev.kauzes.mizan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

/**
 * A balance is cheap to read because it is kept rather than summed, which is also how it
 * becomes wrong. The test that matters here is the one with threads in it.
 */
@SpringBootTest
class BalanceTest extends MizanIntegrationTest {

    private static final Instant WHEN = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anAccountWithNoPostingsHoldsNothing() throws Exception {
        Books books = books();

        mockMvc.perform(get(balanceOf(books, books.cash)).with(books.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
    }

    @Test
    void aPostingMovesBothBalances() throws Exception {
        Books books = books();

        postEntry(books, 125000);

        mockMvc.perform(get(balanceOf(books, books.cash)).with(books.reader()))
                .andExpect(jsonPath("$.balance").value(125000))
                .andExpect(jsonPath("$.normalSide").value("DEBIT"));

        // The other side of the same movement. The sign is the raw sum, not flipped to read
        // naturally for a liability: the type is what says which way to read it.
        mockMvc.perform(get(balanceOf(books, books.owed)).with(books.reader()))
                .andExpect(jsonPath("$.balance").value(-125000))
                .andExpect(jsonPath("$.normalSide").value("CREDIT"));
    }

    @Test
    void balancesAccumulate() throws Exception {
        Books books = books();

        postEntry(books, 125000);
        postEntry(books, 40000);
        postEntry(books, -25000);

        assertThat(balance(books.cash)).isEqualTo(140000L);
        assertThat(balance(books.owed)).isEqualTo(-140000L);
    }

    @Test
    void aListingCarriesTheBalancesToo() throws Exception {
        Books books = books();
        postEntry(books, 125000);

        mockMvc.perform(get("/api/v1/merchants/" + books.merchantId + "/accounts")
                        .with(books.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'cash.try')].balance").value(
                        org.hamcrest.Matchers.contains(125000)));
    }

    @Test
    void manyWritersAtOneAccountLoseNothing() throws Exception {
        Books books = books();
        // More writers than the retry budget ever allowed, which is the point: how many
        // callers may touch an account at once is not something a retry count should decide.
        int writers = 24;
        long each = 1000L;

        // Every one of these reads the same balance and writes it back. They queue on the
        // account rather than racing for it, so none is refused and none is lost.
        CyclicBarrier together = new CyclicBarrier(writers);
        ExecutorService threads = Executors.newFixedThreadPool(writers);

        try {
            List<Future<Integer>> posted = threads.invokeAll(Collections.nCopies(
                    writers,
                    (Callable<Integer>) () -> {
                        together.await();
                        return postEntry(books, each).andReturn().getResponse().getStatus();
                    }));

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : posted) {
                statuses.add(result.get());
            }
            assertThat(statuses)
                    .as("every one of them should have been written, none refused for contention")
                    .containsOnly(201);
        } finally {
            threads.shutdownNow();
        }

        assertThat(balance(books.cash))
                .as("the balance is the sum of the postings, or an update was lost")
                .isEqualTo(writers * each);
        assertThat(balance(books.owed)).isEqualTo(-(writers * each));
        assertThat(balance(books.cash))
                .as("and it agrees with the history behind it")
                .isEqualTo(sumOfPostings(books.cash));
    }

    @Test
    void aBalanceAlwaysAgreesWithTheHistoryBehindIt() throws Exception {
        Books books = books();
        postEntry(books, 125000);
        postEntry(books, 7500);

        assertThat(balance(books.cash)).isEqualTo(sumOfPostings(books.cash));
        assertThat(balance(books.owed)).isEqualTo(sumOfPostings(books.owed));
    }

    @Test
    void aVersionMovesWithEveryPosting() throws Exception {
        Books books = books();
        long before = version(books.cash);

        postEntry(books, 1000);

        assertThat(version(books.cash))
                .as("without this moving, nothing would notice a concurrent write")
                .isGreaterThan(before);
    }

    @Test
    void anotherMerchantCannotReadThisBalance() throws Exception {
        Books mine = books();
        Books theirs = books();

        mockMvc.perform(get(balanceOf(theirs, theirs.cash)).with(mine.reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(balanceOf(mine, theirs.cash)).with(mine.reader()))
                .andExpect(status().isNotFound());
    }

    private long balance(UUID account) {
        return jdbc.queryForObject("select balance from account where id = ?", Long.class, account);
    }

    private long version(UUID account) {
        return jdbc.queryForObject("select version from account where id = ?", Long.class, account);
    }

    private long sumOfPostings(UUID account) {
        Long total = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from posting where account_id = ?",
                Long.class,
                account);
        return total == null ? 0L : total;
    }

    private ResultActions postEntry(Books books, long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/merchants/" + books.merchantId + "/entries")
                        .with(books.writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalReference":"%s","description":"A movement",
                                 "occurredAt":"%s","postings":[
                                  {"accountId":"%s","amount":%d},
                                  {"accountId":"%s","amount":%d}]}
                                """.formatted(
                                UUID.randomUUID(), WHEN, books.cash, amount, books.owed, -amount)))
                .andExpect(status().isCreated());
    }

    private static String balanceOf(Books books, UUID account) {
        return "/api/v1/merchants/" + books.merchantId + "/accounts/" + account + "/balance";
    }

    private record Books(UUID merchantId, UUID userId, UUID cash, UUID owed) {

        RequestPostProcessor writer() {
            return Callers.as(userId, merchantId, Role.ADMIN);
        }

        RequestPostProcessor reader() {
            return Callers.as(userId, merchantId, Role.VIEWER);
        }
    }

    private Books books() {
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        return new Books(
                merchantId,
                userId,
                account(merchantId, userId, "cash.try", "ASSET"),
                account(merchantId, userId, "owed.try", "LIABILITY"));
    }

    private UUID account(UUID merchantId, UUID userId, String code, String type) {
        try {
            String body = mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/accounts")
                            .with(Callers.as(userId, merchantId, Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"%s","name":"%s","type":"%s","currency":"TRY"}
                                    """.formatted(code, code, type)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            return UUID.fromString(
                    tools.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .readTree(body)
                            .path("id")
                            .asString());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
