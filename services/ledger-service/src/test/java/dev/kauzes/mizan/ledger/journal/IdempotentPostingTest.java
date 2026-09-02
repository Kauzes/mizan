package dev.kauzes.mizan.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostEntryRequest;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostingRequest;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens when a caller does not hear back.
 *
 * <p>A client that times out does not know whether the money moved, so it sends the request
 * again. In a ledger, a retry that posts twice is money invented out of a dropped response,
 * which is the whole reason a reference is required rather than offered.
 */
@SpringBootTest
class IdempotentPostingTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant WHEN = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JournalService journal;

    @Test
    void theSameReferenceTwiceYieldsOneEntry() throws Exception {
        Books books = books();
        String reference = "payment:" + UUID.randomUUID() + ":capture";

        JsonNode first = JSON.readTree(bodyOf(
                postEntry(books, reference, 125000).andExpect(status().isCreated())));
        JsonNode second = JSON.readTree(bodyOf(
                postEntry(books, reference, 125000).andExpect(status().isCreated())));

        assertThat(second)
                .as("a retry cannot be told from the call it is retrying")
                .isEqualTo(first);
        assertThat(entriesFor(books)).isEqualTo(1L);
        assertThat(postingsFor(books))
                .as("and it certainly does not post the money twice")
                .isEqualTo(2L);
    }

    @Test
    void aReplayIsAnsweredTheSameWayDownToTheStatus() throws Exception {
        Books books = books();
        String reference = "payment:" + UUID.randomUUID();

        ResultActions first = postEntry(books, reference, 125000);
        ResultActions second = postEntry(books, reference, 125000);

        assertThat(second.andReturn().getResponse().getStatus())
                .as("a client that retries should not have to handle a second shape of success")
                .isEqualTo(first.andReturn().getResponse().getStatus());
        assertThat(second.andReturn().getResponse().getHeader("Location"))
                .isEqualTo(first.andReturn().getResponse().getHeader("Location"));
    }

    @Test
    void theSameReferenceForADifferentEntryIsRefused() throws Exception {
        Books books = books();
        String reference = "payment:" + UUID.randomUUID();

        postEntry(books, reference, 125000).andExpect(status().isCreated());

        postEntry(books, reference, 999999)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail")
                        .value("Reference " + reference + " was already used for a different "
                                + "entry."));
        assertThat(entriesFor(books)).isEqualTo(1L);
    }

    @Test
    void thePostingsMayArriveInEitherOrder() throws Exception {
        Books books = books();
        String reference = "payment:" + UUID.randomUUID();

        postEntry(books, reference, 125000).andExpect(status().isCreated());

        // The same two postings, sent the other way round. A client building its list from a
        // map should not be told its retry is a different request.
        mockMvc.perform(post(entries(books))
                        .with(books.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalReference":"%s","description":"Card payment captured",
                                 "occurredAt":"%s","postings":[
                                  {"accountId":"%s","amount":-125000},
                                  {"accountId":"%s","amount":125000}]}
                                """.formatted(reference, WHEN, books.owed, books.cash)))
                .andExpect(status().isCreated());

        assertThat(entriesFor(books)).isEqualTo(1L);
    }

    @Test
    void tenCallersRacingTheSameReferencePostItOnce() throws Exception {
        Books books = books();
        String reference = "payment:" + UUID.randomUUID();
        int callers = 10;

        // Sequential retries are the easy case. This is the one that matters: the reference
        // is free for all ten of them at the moment they look.
        CyclicBarrier together = new CyclicBarrier(callers);
        ExecutorService threads = Executors.newFixedThreadPool(callers);

        try {
            List<Future<UUID>> results = threads.invokeAll(java.util.Collections.nCopies(
                    callers,
                    (Callable<UUID>) () -> {
                        together.await();
                        return journal.post(books.merchantId, request(books, reference, 125000))
                                .id();
                    }));

            List<UUID> posted = new java.util.ArrayList<>();
            for (Future<UUID> result : results) {
                posted.add(result.get());
            }

            assertThat(posted)
                    .as("every caller should be told about the same entry")
                    .containsOnly(posted.get(0));
        } finally {
            threads.shutdownNow();
        }

        assertThat(entriesFor(books)).as("and there should be one of it").isEqualTo(1L);
        assertThat(postingsFor(books)).isEqualTo(2L);
    }

    @Test
    void twoMerchantsMayUseTheSameReference() throws Exception {
        Books mine = books();
        Books theirs = books();
        String reference = "invoice:2026-09-01:0001";

        postEntry(mine, reference, 125000).andExpect(status().isCreated());
        postEntry(theirs, reference, 125000)
                .andExpect(status().isCreated());

        assertThat(entriesFor(mine)).isEqualTo(1L);
        assertThat(entriesFor(theirs)).isEqualTo(1L);
    }

    @Test
    void anEntryWithoutAReferenceIsNotAccepted() throws Exception {
        Books books = books();

        mockMvc.perform(post(entries(books))
                        .with(books.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Anonymous","occurredAt":"%s","postings":[
                                  {"accountId":"%s","amount":1000},
                                  {"accountId":"%s","amount":-1000}]}
                                """.formatted(WHEN, books.cash, books.owed)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theDatabaseRefusesADuplicateReferenceWrittenDirectly() throws Exception {
        Books books = books();
        String reference = "payment:" + UUID.randomUUID();

        postEntry(books, reference, 125000).andExpect(status().isCreated());

        // Straight into the table, past the service and its careful catch. The reference is
        // unique because the database says so, not because the application checks.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "insert into journal_entry (id, merchant_id, external_reference, "
                                + "request_fingerprint, description, occurred_at, recorded_at) "
                                + "values (?, ?, ?, 'written-in-sql', 'Again', now(), now())",
                        UUID.randomUUID(),
                        books.merchantId,
                        reference))
                .as("uniqueness is the database's answer, not the application's")
                .hasStackTraceContaining("journal_entry_reference_per_merchant");
    }

    private static PostEntryRequest request(Books books, String reference, long amount) {
        return new PostEntryRequest(
                reference,
                "Card payment captured",
                WHEN,
                null,
                List.of(
                        new PostingRequest(books.cash, amount),
                        new PostingRequest(books.owed, -amount)));
    }

    private ResultActions postEntry(Books books, String reference, long amount) throws Exception {
        return mockMvc.perform(post(entries(books))
                .with(books.writer())
                        .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalReference":"%s","description":"Card payment captured",
                         "occurredAt":"%s","postings":[
                          {"accountId":"%s","amount":%d},
                          {"accountId":"%s","amount":%d}]}
                        """.formatted(reference, WHEN, books.cash, amount, books.owed, -amount)));
    }

    private long entriesFor(Books books) {
        return jdbc.queryForObject(
                "select count(*) from journal_entry where merchant_id = ?",
                Long.class,
                books.merchantId);
    }

    private long postingsFor(Books books) {
        return jdbc.queryForObject(
                "select count(*) from posting p join journal_entry e on e.id = p.entry_id "
                        + "where e.merchant_id = ?",
                Long.class,
                books.merchantId);
    }

    private record Books(UUID merchantId, UUID userId, UUID cash, UUID owed) {

        org.springframework.test.web.servlet.request.RequestPostProcessor writer() {
            return Callers.as(userId, merchantId, Role.ADMIN);
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
            String body = bodyOf(mockMvc.perform(post("/api/v1/merchants/" + merchantId
                            + "/accounts")
                    .with(Callers.as(userId, merchantId, Role.ADMIN))
                        .with(Idempotently.freshKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"code":"%s","name":"%s","type":"%s","currency":"TRY"}
                            """.formatted(code, code, type))));
            return UUID.fromString(JSON.readTree(body).path("id").asString());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String entries(Books books) {
        return "/api/v1/merchants/" + books.merchantId + "/entries";
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
