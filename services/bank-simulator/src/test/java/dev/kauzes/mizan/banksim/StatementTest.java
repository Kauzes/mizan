package dev.kauzes.mizan.banksim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The statement, and the fact that it disagrees.
 *
 * <p>The disagreements are the point. A simulator whose statement always matched the platform
 * would let somebody write a reconciliation job that finds nothing, passes every test, and
 * discovers on its first real day that it cannot tell a missing transaction from a matched
 * one. So these tests assert that all three kinds of difference are there, and that asking
 * twice produces the same file — because a reconciliation test that depends on chance is a
 * test that fails on Tuesdays.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsWhatWasCapturedTodayWithAHeaderAndATrailerThatAgree() throws Exception {
        // Faithful, so this is about the format rather than about the differences.
        captured(100_00);
        captured(200_00);

        List<String> lines = statement(LocalDate.now(), true);

        assertThat(lines.getFirst())
                .as("a header naming the acquirer, the day and the currency")
                .startsWith("H|MIZANBANK|" + LocalDate.now() + "|TRY");

        List<String> details = lines.stream().filter(line -> line.startsWith("D|")).toList();
        assertThat(details).isNotEmpty();
        assertThat(details).allSatisfy(line -> assertThat(line.split("\\|")).hasSize(6));

        // The trailer's count and total agree with the rows above it, which is what makes a
        // truncated file detectable rather than merely short.
        String[] trailer = lines.getLast().split("\\|");
        assertThat(trailer[0]).isEqualTo("T");
        assertThat(Integer.parseInt(trailer[1])).isEqualTo(details.size());
        assertThat(Long.parseLong(trailer[2]))
                .isEqualTo(details.stream().mapToLong(StatementTest::amountIn).sum());
    }

    @Test
    void disagreesInAllThreeWaysAtOnce() throws Exception {
        captured(100_00);
        captured(200_00);
        captured(300_00);

        Map<String, Long> faithful = amountsBy(details(statement(LocalDate.now(), true)));
        Map<String, Long> sent = amountsBy(details(statement(LocalDate.now(), false)));

        // Compared as two sets rather than by naming particular transactions: this acquirer
        // keeps what it has decided for as long as it is running, so every test in this class
        // shares a today and no test owns "the first one".

        // One the platform has and this statement does not.
        List<String> missing = faithful.keySet().stream()
                .filter(reference -> !sent.containsKey(reference))
                .toList();
        assertThat(missing).as("exactly one transaction goes missing").hasSize(1);

        // One this statement has that the platform never issued.
        List<String> invented = sent.keySet().stream()
                .filter(reference -> !faithful.containsKey(reference))
                .toList();
        assertThat(invented).hasSize(1);
        assertThat(invented.getFirst()).startsWith(Statements.NEVER_ISSUED);
        assertThat(sent.get(invented.getFirst())).isEqualTo(Statements.PHANTOM_AMOUNT);

        // And one both have, for amounts that differ by a minor unit.
        List<String> differing = sent.keySet().stream()
                .filter(faithful::containsKey)
                .filter(reference -> !sent.get(reference).equals(faithful.get(reference)))
                .toList();
        assertThat(differing).as("exactly one amount disagrees").hasSize(1);
        assertThat(sent.get(differing.getFirst()))
                .isEqualTo(faithful.get(differing.getFirst()) - Statements.OFF_BY);
    }

    @Test
    void answersTheSameWayTwice() throws Exception {
        captured(100_00);
        captured(200_00);

        // A statement that changed under a reader would make every reconciliation a race,
        // and a re-run after an incident would find differences that were never there.
        assertThat(statement(LocalDate.now(), false))
                .isEqualTo(statement(LocalDate.now(), false));
    }

    @Test
    void saysNothingAboutADayNothingSettledOn() throws Exception {
        List<String> lines = statement(LocalDate.of(2020, 1, 1), true);

        // A header and a trailer and no detail rows. An empty file would be indistinguishable
        // from a failed download.
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).startsWith("H|");
        assertThat(lines.getLast()).isEqualTo("T|0|0");
    }

    @Test
    void listsNothingThatWasOnlyAuthorized() throws Exception {
        String held = authorized(500_00);
        String taken = captured(100_00);

        // A statement is money that moved. An authorization is a promise that it is there,
        // and an acquirer that settled promises would be settling payments that may be voided.
        //
        // By reference rather than by counting: this acquirer keeps what it has decided for
        // as long as it is running, so every test in this class shares a today.
        List<String> sent = references(details(statement(LocalDate.now(), true)));
        assertThat(sent).contains(taken).doesNotContain(held);
    }

    @Test
    void listsWhatIsLeftOfSomethingRefunded() throws Exception {
        String reference = captured(100_00);
        mockMvc.perform(post("/acquirer/authorizations/{reference}/refund", reference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25_00,\"reference\":\"back-1\"}".replace("_", "")))
                .andExpect(status().isOk());

        // What an acquirer actually sent on. A refund it processed is money it did not send,
        // and a statement that ignored that would disagree with the bank's own books.
        String row = details(statement(LocalDate.now(), true)).stream()
                .filter(line -> line.contains(reference))
                .findFirst()
                .orElseThrow(() -> new AssertionError(reference + " is not on the statement"));

        assertThat(amountIn(row)).isEqualTo(75_00L);
    }

    // -- helpers ---------------------------------------------------------------------------

    private List<String> statement(LocalDate day, boolean faithful) throws Exception {
        String file = mockMvc.perform(get("/statements/{day}?faithful={faithful}", day, faithful))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return Arrays.stream(file.split("\n")).filter(line -> !line.isBlank()).toList();
    }

    private static List<String> details(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("D|")).toList();
    }

    private static List<String> references(List<String> details) {
        return details.stream().map(line -> line.split("\\|")[1]).toList();
    }

    /** Every detail row as reference to amount, which is what two statements are compared as. */
    private static Map<String, Long> amountsBy(List<String> details) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (String detail : details) {
            amounts.put(detail.split("\\|")[1], amountIn(detail));
        }
        return amounts;
    }

    private static long amountIn(String detail) {
        return Long.parseLong(detail.split("\\|")[3]);
    }

    /** Which reference sorts first, since that is the one the statement leaves out. */
    private static String smallestOf(List<String> references) {
        return references.stream().sorted().findFirst().orElseThrow();
    }

    private String captured(long amount) throws Exception {
        String reference = authorized(amount);
        mockMvc.perform(post("/acquirer/authorizations/{reference}/capture", reference))
                .andExpect(status().isOk());
        return reference;
    }

    private String authorized(long amount) throws Exception {
        String answer = mockMvc.perform(post("/acquirer/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","amount":%d,"currency":"TRY","card":"%s"}
                                """.formatted(UUID.randomUUID(), amount, GOOD_CARD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return JSON.readTree(answer).path("acquirerReference").asString();
    }
}
