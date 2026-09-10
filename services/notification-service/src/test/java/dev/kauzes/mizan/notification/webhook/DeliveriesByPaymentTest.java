package dev.kauzes.mizan.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Did you tell me about this payment?"
 *
 * <p>The endpoint-scoped list answers whether an endpoint is working, which a merchant can
 * mostly see for themselves. This answers the question they actually open a support ticket
 * about, and the reason it needs its own route is that a delivery is found by payment rather
 * than by endpoint — a merchant debugging one order does not know which of their endpoints to
 * look in.
 */
@SpringBootTest(properties = "mizan.webhooks.allow-any-destination=true")
class DeliveriesByPaymentTest extends MizanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookDeliveries deliveries;

    @Autowired
    private WebhookEndpointService endpoints;

    @Test
    void findsWhatWasSentAboutOnePayment() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID endpoint = endpointFor(merchant);
        UUID payment = UUID.randomUUID();
        UUID anotherPayment = UUID.randomUUID();

        queue(merchant, endpoint, payment, "payment.captured");
        queue(merchant, endpoint, anotherPayment, "payment.declined");

        mockMvc.perform(get(deliveriesOf(merchant) + "?paymentId=" + payment)
                        .with(reader(merchant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].event_type").value("payment.captured"))
                // The endpoint comes back with it, so the attempts behind it can be read
                // through that endpoint's own route rather than through a second copy of it.
                .andExpect(jsonPath("$[0].endpoint_id").value(endpoint.toString()));
    }

    @Test
    void andEverythingWhenNoPaymentIsNamed() throws Exception {
        UUID merchant = UUID.randomUUID();
        queue(merchant, endpointFor(merchant), UUID.randomUUID(), "payment.captured");
        queue(merchant, endpointFor(merchant), UUID.randomUUID(), "payment.refunded");

        mockMvc.perform(get(deliveriesOf(merchant)).with(reader(merchant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void neverAnotherMerchantsDeliveries() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        queue(theirs, endpointFor(theirs), payment, "payment.captured");

        // Asking by a payment id that is not mine answers with nothing rather than with
        // somebody else's delivery. A payment id is a guessable-shaped thing to put in a URL.
        mockMvc.perform(get(deliveriesOf(mine) + "?paymentId=" + payment).with(reader(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // And asking about their merchant directly is refused before any of this runs.
        mockMvc.perform(get(deliveriesOf(theirs)).with(reader(mine)))
                .andExpect(status().isForbidden());
    }

    @Test
    void needsThePermissionToReadWebhooks() throws Exception {
        UUID merchant = UUID.randomUUID();

        mockMvc.perform(get(deliveriesOf(merchant))
                        .with(Callers.as(UUID.randomUUID(), merchant, Role.ANALYST)))
                .andExpect(status().isOk());

        mockMvc.perform(get(deliveriesOf(merchant))
                        .with(Callers.as(UUID.randomUUID(), merchant)))
                .andExpect(status().isForbidden());
    }

    @Test
    void answersWithNothingRatherThanAnErrorWhenNothingWasSent() throws Exception {
        UUID merchant = UUID.randomUUID();

        mockMvc.perform(get(deliveriesOf(merchant) + "?paymentId=" + UUID.randomUUID())
                        .with(reader(merchant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void countsAsManyAsAskedForAndNoMore() throws Exception {
        UUID merchant = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            queue(merchant, endpointFor(merchant), UUID.randomUUID(), "payment.captured");
        }

        mockMvc.perform(get(deliveriesOf(merchant) + "?limit=2").with(reader(merchant)))
                .andExpect(jsonPath("$.length()").value(2));

        // A limit a caller composes is a limit somebody can make expensive, so it is bounded
        // rather than believed.
        String body = mockMvc.perform(get(deliveriesOf(merchant) + "?limit=100000")
                        .with(reader(merchant)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).isNotEmpty();
    }

    /** A real endpoint, because a delivery that points at no endpoint is not a delivery. */
    private UUID endpointFor(UUID merchant) {
        return endpoints
                .register(
                        merchant,
                        new WebhookRequests.RegisterEndpointRequest(
                                "https://example.invalid/hook/" + UUID.randomUUID(),
                                "test",
                                java.util.Set.of("payment.captured")))
                .endpoint()
                .id();
    }

    private void queue(UUID merchant, UUID endpoint, UUID payment, String type) {
        UUID delivery = UUID.randomUUID();
        deliveries.queue(
                delivery,
                merchant,
                endpoint,
                UUID.randomUUID(),
                payment,
                type,
                "{\"id\":\"" + delivery + "\",\"type\":\"" + type + "\"}");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader(
            UUID merchant) {
        return Callers.as(UUID.randomUUID(), merchant, Role.VIEWER);
    }

    private static String deliveriesOf(UUID merchant) {
        return "/api/v1/merchants/" + merchant + "/webhook-deliveries";
    }
}
