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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Opening accounts, reading them back, and not reading anybody else's. */
@SpringBootTest
class ChartOfAccountsTest extends MizanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void opensAnAccountAndReadsItBack() throws Exception {
        Merchant merchant = merchant();

        String location = open(merchant, "settlement.try", "Settlement, TRY", "LIABILITY", "TRY")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("settlement.try"))
                .andExpect(jsonPath("$.type").value("LIABILITY"))
                .andExpect(jsonPath("$.normalSide").value("CREDIT"))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location).with(merchant.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Settlement, TRY"));
    }

    @Test
    void listsWhatAMerchantHasOpened() throws Exception {
        Merchant merchant = merchant();
        open(merchant, "settlement.try", "Settlement", "LIABILITY", "TRY")
                .andExpect(status().isCreated());
        open(merchant, "fees.try", "Fees paid", "EXPENSE", "TRY").andExpect(status().isCreated());

        mockMvc.perform(get(accounts(merchant)).with(merchant.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("fees.try"));
    }

    @Test
    void refusesASecondAccountWithTheSameCode() throws Exception {
        Merchant merchant = merchant();
        open(merchant, "settlement.try", "Settlement", "LIABILITY", "TRY")
                .andExpect(status().isCreated());

        open(merchant, "settlement.try", "Settlement again", "LIABILITY", "TRY")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void twoMerchantsMayUseTheSameCode() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();

        open(mine, "settlement.try", "Mine", "LIABILITY", "TRY").andExpect(status().isCreated());
        // A code identifies an account within one merchant's books, not across the platform.
        open(theirs, "settlement.try", "Theirs", "LIABILITY", "TRY")
                .andExpect(status().isCreated());
    }

    @Test
    void refusesThreeLettersThatAreNotACurrency() throws Exception {
        Merchant merchant = merchant();

        open(merchant, "nonsense", "Nonsense", "ASSET", "XYZ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test
    void refusesACodeOrTypeThatIsNotOne() throws Exception {
        Merchant merchant = merchant();

        open(merchant, "Not A Code", "Bad code", "ASSET", "TRY")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(accounts(merchant))
                        .with(merchant.manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"x","name":"X","type":"SOMETHING","currency":"TRY"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotReachAnotherMerchantsAccounts() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();

        String location = open(theirs, "settlement.try", "Theirs", "LIABILITY", "TRY")
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID theirAccount = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(get(accounts(theirs)).with(mine.reader()))
                .andExpect(status().isForbidden());

        // Their account id, under my own merchant's path: the lookup is scoped, so from here
        // it does not exist rather than existing and being hidden.
        mockMvc.perform(get(accounts(mine) + "/" + theirAccount).with(mine.reader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePlatformsOwnAccountsAreThereAndBelongToNobody() {
        Long platformAccounts = jdbc.queryForObject(
                "select count(*) from account where merchant_id is null", Long.class);

        assertThat(platformAccounts)
                .as("seeded by migration, because a chart of accounts is decided rather than "
                        + "arrived at")
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "select type from account where code = 'platform.clearing.try'",
                        String.class))
                .isEqualTo("ASSET");
    }

    @Test
    void noMerchantCanReachAPlatformAccount() throws Exception {
        Merchant merchant = merchant();
        UUID platformAccount = jdbc.queryForObject(
                "select id from account where code = 'platform.clearing.try'", UUID.class);

        mockMvc.perform(get(accounts(merchant) + "/" + platformAccount).with(merchant.reader()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(accounts(merchant)).with(merchant.reader()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void openingAnAccountIsNotSomethingEveryRoleMayDo() throws Exception {
        Merchant merchant = merchant();

        mockMvc.perform(post(accounts(merchant))
                        .with(merchant.as(Role.ANALYST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"sneaky","name":"Sneaky","type":"ASSET","currency":"TRY"}
                                """))
                .andExpect(status().isForbidden());

        // Reading the books, though, is what a viewer is for.
        mockMvc.perform(get(accounts(merchant)).with(merchant.as(Role.VIEWER)))
                .andExpect(status().isOk());
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor manager() {
            return Callers.as(userId, id, Role.ADMIN);
        }

        RequestPostProcessor reader() {
            return Callers.as(userId, id, Role.VIEWER);
        }

        RequestPostProcessor as(Role role) {
            return Callers.as(userId, id, role);
        }
    }

    /** No merchant table here: identity owns those, and this service is told the id. */
    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }

    private ResultActions open(
            Merchant merchant, String code, String name, String type, String currency)
            throws Exception {

        return mockMvc.perform(post(accounts(merchant))
                .with(merchant.manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","name":"%s","type":"%s","currency":"%s"}
                        """.formatted(code, name, type, currency)));
    }

    private static String accounts(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/accounts";
    }
}
