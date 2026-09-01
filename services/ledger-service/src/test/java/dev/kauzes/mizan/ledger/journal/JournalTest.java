package dev.kauzes.mizan.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The invariant the platform rests on, checked from both sides.
 *
 * <p>Half of these go through the API, which is where a caller meets it. The other half go
 * around the application entirely and write SQL, because a rule that only holds for well
 * behaved application code is a comment rather than a rule, and the ledger is the one place
 * that distinction is worth paying for.
 */
@SpringBootTest
class JournalTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WHEN = "2026-09-01T10:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void postsABalancedEntryAndReadsItBack() throws Exception {
        Books books = books();

        String location = postEntry(books, """
                {"description":"Card payment captured","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-125000}]}
                """.formatted(WHEN, books.cash, books.owed))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postings.length()").value(2))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location).with(books.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Card payment captured"))
                .andExpect(jsonPath("$.occurredAt").value(WHEN))
                .andExpect(jsonPath("$.postings[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.postings[0].currency").value("TRY"))
                .andExpect(jsonPath("$.postings[1].direction").value("CREDIT"));
    }

    @Test
    void refusesAnEntryThatDoesNotBalance() throws Exception {
        Books books = books();

        postEntry(books, """
                {"description":"Money from nowhere","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-100000}]}
                """.formatted(WHEN, books.cash, books.owed))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "The postings do not balance in TRY: they sum to 25000 rather than zero."));
    }

    @Test
    void refusesAnEntryWithOnlyOneSide() throws Exception {
        Books books = books();

        postEntry(books, """
                {"description":"Half a movement","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000}]}
                """.formatted(WHEN, books.cash))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refusesAPostingOfNothing() throws Exception {
        Books books = books();

        postEntry(books, """
                {"description":"Nothing at all","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":0},
                  {"accountId":"%s","amount":0}]}
                """.formatted(WHEN, books.cash, books.owed))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("A posting of zero moves nothing."));
    }

    @Test
    void refusesAnAccountOutsideTheseBooks() throws Exception {
        Books mine = books();
        Books theirs = books();

        postEntry(mine, """
                {"description":"Reaching across","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-125000}]}
                """.formatted(WHEN, mine.cash, theirs.owed))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "No account " + theirs.owed + " in this merchant's books."));
    }

    @Test
    void refusesAPlatformAccountThroughAMerchantsPath() throws Exception {
        Books books = books();
        UUID platform = jdbc.queryForObject(
                "select id from account where code = 'platform.clearing.try'", UUID.class);

        // The platform's accounts are real and are posted to, but not by a merchant reaching
        // through their own path. MIZ-4 posts both sides from inside the platform.
        postEntry(books, """
                {"description":"Helping myself","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-125000}]}
                """.formatted(WHEN, platform, books.owed))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void balancesEachCurrencySeparately() throws Exception {
        Books books = books();
        UUID dollars = account(books, "cash.usd", "Cash, USD", "ASSET", "USD");
        UUID owedDollars = account(books, "owed.usd", "Owed, USD", "LIABILITY", "USD");

        // Two currencies in one entry, each side balancing within its own currency. A single
        // sum across both would be meaningless and would happen to be zero here.
        postEntry(books, """
                {"description":"Two currencies at once","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-125000},
                  {"accountId":"%s","amount":4000},
                  {"accountId":"%s","amount":-4000}]}
                """.formatted(WHEN, books.cash, books.owed, dollars, owedDollars))
                .andExpect(status().isCreated());
    }

    @Test
    void refusesAnEntryThatOnlyBalancesIfCurrenciesAreAddedTogether() throws Exception {
        Books books = books();
        UUID dollars = account(books, "cash.usd", "Cash, USD", "ASSET", "USD");

        postEntry(books, """
                {"description":"Lira for dollars, as though they were the same","occurredAt":"%s",
                 "postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-125000}]}
                """.formatted(WHEN, books.cash, dollars))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("do not balance in")));
    }

    @Test
    void correctsAnEarlierEntryRatherThanChangingIt() throws Exception {
        Books books = books();
        UUID original = idOf(postEntry(books, """
                {"description":"Captured twice by mistake","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":125000},
                  {"accountId":"%s","amount":-125000}]}
                """.formatted(WHEN, books.cash, books.owed)));

        postEntry(books, """
                {"description":"Reversing the duplicate","occurredAt":"%s","corrects":"%s",
                 "postings":[
                  {"accountId":"%s","amount":-125000},
                  {"accountId":"%s","amount":125000}]}
                """.formatted(WHEN, original, books.cash, books.owed))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrects").value(original.toString()));

        mockMvc.perform(get(entries(books)).with(books.reader()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void refusesToCorrectAnEntryFromAnotherMerchant() throws Exception {
        Books mine = books();
        Books theirs = books();
        UUID theirEntry = idOf(postEntry(theirs, """
                {"description":"Theirs","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":1000},{"accountId":"%s","amount":-1000}]}
                """.formatted(WHEN, theirs.cash, theirs.owed)));

        postEntry(mine, """
                {"description":"Correcting somebody else's books","occurredAt":"%s","corrects":"%s",
                 "postings":[
                  {"accountId":"%s","amount":1000},{"accountId":"%s","amount":-1000}]}
                """.formatted(WHEN, theirEntry, mine.cash, mine.owed))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void cannotReadAnotherMerchantsEntries() throws Exception {
        Books mine = books();
        Books theirs = books();
        UUID theirEntry = idOf(postEntry(theirs, """
                {"description":"Theirs","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":1000},{"accountId":"%s","amount":-1000}]}
                """.formatted(WHEN, theirs.cash, theirs.owed)));

        mockMvc.perform(get(entries(theirs)).with(mine.reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(entries(mine) + "/" + theirEntry).with(mine.reader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingIsNotPosting() throws Exception {
        Books books = books();

        mockMvc.perform(get(entries(books)).with(books.as(Role.VIEWER)))
                .andExpect(status().isOk());
        mockMvc.perform(post(entries(books))
                        .with(books.as(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Not mine to write","occurredAt":"%s","postings":[
                                  {"accountId":"%s","amount":1000},
                                  {"accountId":"%s","amount":-1000}]}
                                """.formatted(WHEN, books.cash, books.owed)))
                .andExpect(status().isForbidden());
    }

    // The half that goes around the application. If these pass only because the service is
    // careful, they are not testing what they claim to test.

    @Test
    void theDatabaseRefusesAnUnbalancedEntryWrittenDirectly() {
        Books books = books();

        assertThatThrownBy(() -> inOneTransaction(() -> {
            UUID entry = writeEntry(books, "Straight into the table");
            writePosting(entry, books.cash, 125000);
            writePosting(entry, books.owed, -100000);
        }))
                // Raised by the trigger at commit, so it arrives wrapped in whatever was
                // trying to commit. What matters is that it arrives.
                .as("the constraint has to hold against whatever writes to the table")
                .hasStackTraceContaining("does not balance in TRY")
                .hasStackTraceContaining("sum to 25000");
    }

    @Test
    void theDatabaseRefusesAnEntryWithASinglePosting() {
        Books books = books();

        assertThatThrownBy(() -> inOneTransaction(() -> {
            UUID entry = writeEntry(books, "Only one side");
            writePosting(entry, books.cash, 125000);
        }))
                .hasStackTraceContaining("at least two accounts");
    }

    @Test
    void theDatabaseRefusesAnEntryWithNoPostingsAtAll() {
        Books books = books();

        assertThatThrownBy(() -> inOneTransaction(() -> writeEntry(books, "Nothing follows")))
                .as("an entry with no postings balances trivially and means nothing")
                .hasStackTraceContaining("has 0 posting(s)");
    }

    @Test
    void theDatabaseRefusesAnUpdateOrADelete() throws Exception {
        Books books = books();
        UUID entry = idOf(postEntry(books, """
                {"description":"Written once","occurredAt":"%s","postings":[
                  {"accountId":"%s","amount":1000},{"accountId":"%s","amount":-1000}]}
                """.formatted(WHEN, books.cash, books.owed)));

        assertThatThrownBy(() -> jdbc.update(
                        "update journal_entry set description = 'edited' where id = ?", entry))
                .hasMessageContaining("append only");

        assertThatThrownBy(() -> jdbc.update("delete from posting where entry_id = ?", entry))
                .hasMessageContaining("append only");

        assertThatThrownBy(() -> jdbc.update("delete from journal_entry where id = ?", entry))
                .hasMessageContaining("append only");

        mockMvc.perform(get(entries(books) + "/" + entry).with(books.reader()))
                .andExpect(jsonPath("$.description").value("Written once"));
    }

    @Test
    void theDatabaseAcceptsABalancedEntryWrittenDirectly() {
        Books books = books();

        // The mirror of the refusals above: the trigger is deferred, so a legitimate entry
        // built one statement at a time still commits.
        inOneTransaction(() -> {
            UUID entry = writeEntry(books, "Perfectly good");
            writePosting(entry, books.cash, 7500);
            writePosting(entry, books.owed, -7500);
        });

        assertThat(jdbc.queryForObject(
                        "select count(*) from journal_entry where merchant_id = ?",
                        Long.class,
                        books.merchantId))
                .isEqualTo(1L);
    }

    private void inOneTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    private UUID writeEntry(Books books, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into journal_entry (id, merchant_id, description, occurred_at, recorded_at)"
                        + " values (?, ?, ?, now(), now())",
                id,
                books.merchantId,
                description);
        return id;
    }

    private void writePosting(UUID entry, UUID account, long amount) {
        jdbc.update(
                "insert into posting (id, entry_id, account_id, amount) values (?, ?, ?, ?)",
                UUID.randomUUID(),
                entry,
                account,
                amount);
    }

    private record Books(UUID merchantId, UUID userId, UUID cash, UUID owed) {

        RequestPostProcessor writer() {
            return Callers.as(userId, merchantId, Role.ADMIN);
        }

        RequestPostProcessor reader() {
            return Callers.as(userId, merchantId, Role.VIEWER);
        }

        RequestPostProcessor as(Role role) {
            return Callers.as(userId, merchantId, role);
        }
    }

    /** A merchant with somewhere for money to come from and somewhere for it to go. */
    private Books books() {
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Books empty = new Books(merchantId, userId, null, null);

        return new Books(
                merchantId,
                userId,
                account(empty, "cash.try", "Cash at the acquirer, TRY", "ASSET", "TRY"),
                account(empty, "owed.try", "Owed to the merchant, TRY", "LIABILITY", "TRY"));
    }

    private UUID account(Books books, String code, String name, String type, String currency) {
        try {
            String body = bodyOf(mockMvc.perform(
                    post("/api/v1/merchants/" + books.merchantId + "/accounts")
                            .with(Callers.as(books.userId, books.merchantId, Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"%s","name":"%s","type":"%s","currency":"%s"}
                                    """.formatted(code, name, type, currency))));
            return UUID.fromString(JSON.readTree(body).path("id").asString());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ResultActions postEntry(Books books, String body) throws Exception {
        return mockMvc.perform(post(entries(books))
                .with(books.writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String entries(Books books) {
        return "/api/v1/merchants/" + books.merchantId + "/entries";
    }

    private static UUID idOf(ResultActions actions) throws Exception {
        return UUID.fromString(JSON.readTree(bodyOf(actions)).path("id").asString());
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
