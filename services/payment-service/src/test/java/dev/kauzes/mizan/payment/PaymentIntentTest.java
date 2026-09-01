package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.money.Money;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Creating a payment, reading it back, and being refused the things a payment cannot do. */
@SpringBootTest
class PaymentIntentTest extends MizanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsAnIntentAndReadsItBack() throws Exception {
        Merchant merchant = merchant();
        String reference = "order-" + UUID.randomUUID();

        String location = create(merchant, 125000, "TRY", reference)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(125000))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.allowedNext.length()").value(2))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location).with(merchant.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].from").doesNotExist())
                .andExpect(jsonPath("$.history[0].to").value("CREATED"));
    }

    @Test
    void anIntentContactsNobodyAndMovesNothing() throws Exception {
        Merchant merchant = merchant();

        create(merchant, 125000, "TRY", "order-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"));

        // Nothing here talks to an acquirer or a ledger yet, and the state says so: a payment
        // that has been attempted is not CREATED any more.
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment where merchant_id = ? and status <> 'CREATED'",
                        Long.class,
                        merchant.id))
                .isZero();
    }

    @Test
    void refusesASecondPaymentWithTheSameReference() throws Exception {
        Merchant merchant = merchant();
        String reference = "order-" + UUID.randomUUID();

        create(merchant, 125000, "TRY", reference).andExpect(status().isCreated());
        create(merchant, 125000, "TRY", reference)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void twoMerchantsMayUseTheSameReference() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();
        String reference = "order-0001";

        create(mine, 125000, "TRY", reference).andExpect(status().isCreated());
        create(theirs, 125000, "TRY", reference).andExpect(status().isCreated());
    }

    @Test
    void refusesAPaymentForNothingOrForACurrencyThatIsNotOne() throws Exception {
        Merchant merchant = merchant();

        create(merchant, 0, "TRY", "order-" + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        create(merchant, -100, "TRY", "order-" + UUID.randomUUID())
                .andExpect(status().isBadRequest());

        create(merchant, 125000, "XYZ", "order-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test
    void anIllegalTransitionSaysWhereThePaymentIsAndWhereItWasAsked() {
        Payment payment = new Payment(
                UUID.randomUUID(), Money.of(125000, "TRY"), "order-1", "Two bags of coffee");

        // CREATED cannot become CAPTURED: an authorization has to happen in between.
        assertThatThrownBy(() -> payment.moveTo(PaymentStatus.CAPTURED, null))
                .hasMessageContaining("A payment that is CREATED cannot become CAPTURED")
                .hasMessageContaining("It can only become");
    }

    @Test
    void aFinishedPaymentSaysThatIsWhereItEnds() {
        Payment payment = new Payment(UUID.randomUUID(), Money.of(1000, "TRY"), "order-2", null);
        payment.moveTo(PaymentStatus.DECLINED, "insufficient funds");

        assertThatThrownBy(() -> payment.moveTo(PaymentStatus.AUTHORIZED, null))
                .hasMessageContaining("That is where this payment ends.");
    }

    @Test
    void everyStepIsRecordedWithWhyAndWhen() {
        Payment payment = new Payment(UUID.randomUUID(), Money.of(1000, "TRY"), "order-3", null);
        payment.moveTo(PaymentStatus.AUTHORIZED, null);
        payment.moveTo(PaymentStatus.VOIDED, "the customer changed their mind");

        assertThat(payment.history()).hasSize(3);
        assertThat(payment.history().get(2).from()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.history().get(2).to()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(payment.history().get(2).reason())
                .isEqualTo("the customer changed their mind");
        assertThat(payment.history().get(2).at()).isNotNull();
    }

    @Test
    void cannotReadAnotherMerchantsPayment() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();

        String location = create(theirs, 125000, "TRY", "order-" + UUID.randomUUID())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID theirPayment = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(get(payments(theirs)).with(mine.reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(payments(mine) + "/" + theirPayment).with(mine.reader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingIsNotStarting() throws Exception {
        Merchant merchant = merchant();

        mockMvc.perform(get(payments(merchant)).with(merchant.as(Role.VIEWER)))
                .andExpect(status().isOk());
        mockMvc.perform(post(payments(merchant))
                        .with(merchant.as(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000,"currency":"TRY","reference":"not-mine"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void theHistoryCannotBeRewritten() throws Exception {
        Merchant merchant = merchant();
        create(merchant, 1000, "TRY", "order-" + UUID.randomUUID()).andExpect(status().isCreated());

        UUID transition = jdbc.queryForObject(
                "select t.id from payment_transition t join payment p on p.id = t.payment_id "
                        + "where p.merchant_id = ?",
                UUID.class,
                merchant.id);

        assertThatThrownBy(() -> jdbc.update(
                        "update payment_transition set to_status = 'CAPTURED' where id = ?",
                        transition))
                .hasStackTraceContaining("append only");
        assertThatThrownBy(() ->
                        jdbc.update("delete from payment_transition where id = ?", transition))
                .hasStackTraceContaining("append only");
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }

        RequestPostProcessor reader() {
            return Callers.as(userId, id, Role.VIEWER);
        }

        RequestPostProcessor as(Role role) {
            return Callers.as(userId, id, role);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }

    private ResultActions create(Merchant merchant, long amount, String currency, String reference)
            throws Exception {

        return mockMvc.perform(post(payments(merchant))
                .with(merchant.writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":%d,"currency":"%s","reference":"%s",
                         "description":"Two bags of coffee"}
                        """.formatted(amount, currency, reference)));
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }
}
