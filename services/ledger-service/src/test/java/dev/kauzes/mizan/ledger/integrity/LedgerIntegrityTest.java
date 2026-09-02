package dev.kauzes.mizan.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.ledger.integrity.IntegrityReport.CurrencyTotal;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The check that would have caught a bug in any of the four stories before it.
 *
 * <p>Which is why these tests break the ledger on purpose. A check nobody has watched fail is
 * a check nobody knows works, and this one exists precisely for the failures the other tests
 * cannot see.
 */
@SpringBootTest
class LedgerIntegrityTest extends MizanIntegrationTest {

    private static final Instant WHEN = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LedgerIntegrityService integrity;

    @Test
    void aLedgerThatHasOnlyBeenWrittenProperlyIsSound() throws Exception {
        Books books = books();
        postEntry(books, 125000);
        postEntry(books, 7500);

        IntegrityReport report = integrity.check();

        assertThat(report.sound()).as(report.summary()).isTrue();
        assertThat(report.drifted()).isEmpty();
        assertThat(report.totals())
                .as("every currency the books touch is accounted for")
                .isNotEmpty()
                .allSatisfy(total -> assertThat(total.balances()).isTrue());
        assertThat(report.summary()).contains("balances in every currency");
    }

    @Test
    void everyCurrencySumsToZeroOnItsOwn() throws Exception {
        Books books = books();
        postEntry(books, 125000);

        UUID dollars = account(books, "cash.usd", "ASSET", "USD");
        UUID owedDollars = account(books, "owed.usd", "LIABILITY", "USD");
        postEntryBetween(books, dollars, owedDollars, 4000);

        IntegrityReport report = integrity.check();

        assertThat(report.totals().stream().map(CurrencyTotal::currency))
                .contains("TRY", "USD");
        assertThat(report.sound()).isTrue();
    }

    @Test
    void aBalanceQuietlyEditedIsCaught() {
        Books books = books();

        // The failure no other test in this epic can see. Everywhere else the balance and the
        // postings are written by the same code in the same transaction, so a bug consistent
        // about itself would look right to all of them.
        jdbc.update("update account set balance = balance + 999 where id = ?", books.cash);

        try {
            IntegrityReport report = integrity.check();

            assertThat(report.sound()).isFalse();
            assertThat(report.drifted()).singleElement().satisfies(drifted -> {
                assertThat(drifted.accountId()).isEqualTo(books.cash);
                assertThat(drifted.code()).isEqualTo("cash.try");
                assertThat(drifted.outBy())
                        .as("and it says by how much, because otherwise nobody can act on it")
                        .isEqualTo(999L);
            });
            assertThat(report.summary()).contains("1 account(s) disagree");
        } finally {
            jdbc.update("update account set balance = balance - 999 where id = ?", books.cash);
        }

        assertThat(integrity.check().sound()).as("and sound again once repaired").isTrue();
    }

    @Test
    void aLedgerThatDoesNotSumToZeroIsCaught() {
        Books books = books();
        UUID entry = UUID.randomUUID();

        // The triggers from MIZ-35 make this impossible through any ordinary route, which is
        // the point of them. They are stood down here so the check itself can be watched
        // failing, and put back immediately: a check nobody has seen fail is a check nobody
        // knows works.
        withoutTheJournalsGuards(() -> {
            jdbc.update(
                    "insert into journal_entry (id, merchant_id, external_reference, "
                            + "request_fingerprint, description, occurred_at, recorded_at) "
                            + "values (?, ?, ?, 'broken', 'Money from nowhere', now(), now())",
                    entry,
                    books.merchantId,
                    "broken:" + entry);
            jdbc.update(
                    "insert into posting (id, entry_id, account_id, amount) values (?, ?, ?, 500)",
                    UUID.randomUUID(),
                    entry,
                    books.cash);
        });

        try {
            IntegrityReport report = integrity.check();

            assertThat(report.sound()).isFalse();
            assertThat(report.totals())
                    .filteredOn(total -> total.currency().equals("TRY"))
                    .singleElement()
                    .satisfies(total -> assertThat(total.total()).isEqualTo(500L));
            assertThat(report.summary()).contains("TRY out by 500");
        } finally {
            withoutTheJournalsGuards(() -> {
                jdbc.update("delete from posting where entry_id = ?", entry);
                jdbc.update("delete from journal_entry where id = ?", entry);
            });
        }

        assertThat(integrity.check().sound()).as("and sound again once repaired").isTrue();
    }

    @Test
    void theCheckIsReachableAsAnOperation() throws Exception {
        Books books = books();
        postEntry(books, 1000);

        String body = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/actuator/ledgerintegrity"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var report = JsonMapper.builder().build().readTree(body);
        assertThat(report.path("sound").asBoolean()).isTrue();
        assertThat(report.path("checkedAt").asString()).isNotEmpty();
        assertThat(report.path("totals").isArray()).isTrue();
        assertThat(report.path("summary").asString())
                .as("one line an alert can carry")
                .isNotEmpty();
    }

    @Test
    void aReportOfDriftCarriesEnoughToRepairIt() throws Exception {
        Books books = books();
        postEntry(books, 125000);
        jdbc.update("update account set balance = balance + 777 where id = ?", books.cash);

        try {
            String body = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/actuator/ledgerintegrity"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);

            var drifted = JsonMapper.builder().build().readTree(body).path("drifted");

            // Everything needed to write the repair, without going back to the database to
            // work out what the numbers should have been. That is not a hypothetical: the
            // first drift this check ever found was repaired straight from this output.
            assertThat(drifted).singleElement().satisfies(account -> {
                assertThat(account.path("accountId").asString())
                        .isEqualTo(books.cash.toString());
                assertThat(account.path("code").asString()).isEqualTo("cash.try");
                assertThat(account.path("keptBalance").asLong()).isEqualTo(125777L);
                assertThat(account.path("postingsTotal").asLong()).isEqualTo(125000L);
                assertThat(account.path("outBy").asLong()).isEqualTo(777L);
            });
        } finally {
            jdbc.update("update account set balance = balance - 777 where id = ?", books.cash);
        }
    }

    @Test
    void theCheckTakesNoLocksAndBlocksNoWrites() throws Exception {
        Books books = books();
        postEntry(books, 1000);

        // Run the check and a posting at the same time. If the check held anything, this
        // would deadlock or block rather than finish.
        Thread writing = new Thread(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    postEntry(books, 100);
                }
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });

        writing.start();
        for (int i = 0; i < 5; i++) {
            integrity.check();
        }
        writing.join(java.time.Duration.ofSeconds(30).toMillis());

        assertThat(writing.isAlive()).as("writes should not have been held up").isFalse();
        assertThat(integrity.check().sound()).isTrue();
    }

    /** Stands the append only and balance triggers down, and always puts them back. */
    private void withoutTheJournalsGuards(Runnable damage) {
        jdbc.execute("alter table journal_entry disable trigger user");
        jdbc.execute("alter table posting disable trigger user");
        try {
            damage.run();
        } finally {
            jdbc.execute("alter table journal_entry enable trigger user");
            jdbc.execute("alter table posting enable trigger user");
        }
    }

    private record Books(UUID merchantId, UUID userId, UUID cash, UUID owed) {
    }

    private Books books() {
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Books empty = new Books(merchantId, userId, null, null);
        return new Books(
                merchantId,
                userId,
                account(empty, "cash.try", "ASSET", "TRY"),
                account(empty, "owed.try", "LIABILITY", "TRY"));
    }

    private UUID account(Books books, String code, String type, String currency) {
        try {
            String body = mockMvc.perform(post("/api/v1/merchants/" + books.merchantId
                            + "/accounts")
                    .with(Callers.as(books.userId, books.merchantId, Role.ADMIN))
                        .with(Idempotently.freshKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"code":"%s","name":"%s","type":"%s","currency":"%s"}
                            """.formatted(code, code, type, currency)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            return UUID.fromString(
                    JsonMapper.builder().build().readTree(body).path("id").asString());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void postEntry(Books books, long amount) throws Exception {
        postEntryBetween(books, books.cash, books.owed, amount);
    }

    private void postEntryBetween(Books books, UUID debit, UUID credit, long amount)
            throws Exception {

        mockMvc.perform(post("/api/v1/merchants/" + books.merchantId + "/entries")
                        .with(Callers.as(books.userId, books.merchantId, Role.ADMIN))
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalReference":"%s","description":"A movement",
                                 "occurredAt":"%s","postings":[
                                  {"accountId":"%s","amount":%d},
                                  {"accountId":"%s","amount":%d}]}
                                """.formatted(
                                UUID.randomUUID(), WHEN, debit, amount, credit, -amount)))
                .andExpect(status().isCreated());
    }
}
