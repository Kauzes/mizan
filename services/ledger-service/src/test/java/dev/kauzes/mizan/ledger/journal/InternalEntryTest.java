package dev.kauzes.mizan.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.identity.ServiceCredential;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one entry a merchant may not write for themselves.
 *
 * <p>A capture moves money between the platform's clearing account and a merchant's
 * settlement account. Both sides are needed and one of them belongs to nobody in particular,
 * so this cannot go through a merchant-scoped endpoint: one that let a merchant name the
 * platform's clearing account would let them credit themselves out of it.
 *
 * <p>What is checked here is that widening the books admits the platform's accounts and
 * nothing else, and that the credential is what opens the door at all.
 */
@SpringBootTest
class InternalEntryTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WHEN = "2026-09-02T10:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${mizan.internal.service-token}")
    private String serviceToken;

    @Test
    void postsAnEntryThatTouchesBothSetsOfBooks() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        String body = bodyOf(internally(captureOf(merchant, reference()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postings.length()").value(2))
                .andExpect(jsonPath("$.merchantId").value(merchant.id.toString())));

        // The platform holds more at the acquirer and owes the merchant more. That is the
        // whole of what a capture means, and it is two postings that sum to zero.
        assertThat(JSON.readTree(body).path("postings").toString())
                .contains("platform.clearing.try")
                .contains("settlement.try")
                .contains("DEBIT")
                .contains("CREDIT");
    }

    @Test
    void refusesACallWithNoServiceCredential() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        // The point of the whole arrangement. This endpoint does what no merchant may, so a
        // merchant's token would not be enough, and nothing at all certainly is not.
        mockMvc.perform(post("/internal/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captureOf(merchant, reference())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail")
                        .value("This endpoint is not part of the public API."));
    }

    @Test
    void refusesACallWithTheWrongServiceCredential() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        mockMvc.perform(post("/internal/entries")
                        .header(ServiceCredential.HEADER, "a-token-somebody-guessed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captureOf(merchant, reference())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reachesOnlyTheBooksOfTheMerchantItNames() throws Exception {
        Merchant mine = merchantWithASettlementAccount();
        Merchant theirs = merchantWithASettlementAccount();

        internally(captureOf(theirs, reference())).andExpect(status().isCreated());

        // Widening the books admits the platform's accounts. It does not put two merchants
        // in reach of each other: the merchant is named in the request, the codes resolve
        // within them, and nothing landed in mine.
        assertThat(entriesOf(mine.id)).isZero();
        assertThat(entriesOf(theirs.id)).isEqualTo(1);
    }

    @Test
    void refusesAnAccountNobodyOpened() throws Exception {
        Merchant merchant = merchant();

        // The ledger does not open an account because a request arrived, so a merchant who
        // was never set up cannot be captured for. The refusal names the account, because
        // "unprocessable" is not something an operator can act on.
        internally(captureOf(merchant, reference()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("No account settlement.try in this merchant's books. It has to "
                                + "be opened before money can be recorded as arriving in it."));
    }

    @Test
    void refusesAnEntryThatDoesNotBalance() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        // The credential says who is calling. It does not say the books may be wrong.
        internally(entry(merchant, reference(), 125000, -100000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("The postings do not balance in TRY: they sum to 25000 rather "
                                + "than zero."));
    }

    @Test
    void postingTheSameCaptureTwiceWritesOneEntry() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        String reference = reference();

        String first = idOf(internally(captureOf(merchant, reference))
                .andExpect(status().isCreated()));
        String second = idOf(internally(captureOf(merchant, reference))
                .andExpect(status().isCreated()));

        assertThat(second)
                .as("a repeated capture is answered with the entry the first one wrote, which "
                        + "is what lets one whose answer was lost be finished by repeating it")
                .isEqualTo(first);
        assertThat(entriesOf(merchant.id)).isEqualTo(1);
    }

    private ResultActions internally(String body) throws Exception {
        return mockMvc.perform(post("/internal/entries")
                .header(ServiceCredential.HEADER, serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String captureOf(Merchant merchant, String reference) {
        return entry(merchant, reference, 125000, -125000);
    }

    private static String entry(Merchant merchant, String reference, long debit, long credit) {
        return """
                {"merchantId":"%s","externalReference":"%s",
                 "description":"Card payment captured","occurredAt":"%s",
                 "postings":[{"accountCode":"platform.clearing.try","amount":%d},
                             {"accountCode":"settlement.try","amount":%d}]}
                """
                .formatted(merchant.id, reference, WHEN, debit, credit);
    }

    private static String reference() {
        return "payment:" + UUID.randomUUID() + ":capture";
    }

    private long entriesOf(UUID merchantId) {
        Long counted = jdbc.queryForObject(
                "select count(*) from journal_entry where merchant_id = ?", Long.class, merchantId);
        return counted == null ? 0 : counted;
    }

    private String idOf(ResultActions actions) throws Exception {
        return JSON.readTree(bodyOf(actions)).path("id").asString();
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Merchant merchantWithASettlementAccount() throws Exception {
        Merchant merchant = merchant();
        mockMvc.perform(post("/api/v1/merchants/" + merchant.id + "/accounts")
                        .with(Callers.as(merchant.userId, merchant.id, Role.ADMIN))
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"settlement.try","name":"Owed to the merchant, TRY",
                                 "type":"LIABILITY","currency":"TRY"}
                                """))
                .andExpect(status().isCreated());
        return merchant;
    }

    private record Merchant(UUID id, UUID userId) {
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }
}
