package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The hop nobody can reconstruct by hand.
 *
 * <p>Every other link in a trace is made by a header on a request that is happening now. The
 * outbox is not like that: the request that caused the event finished minutes ago, the relay
 * publishes from a scheduler on a thread with no relation to it, and the consumer picks it up
 * in another process entirely. Nothing but the row itself was present for both ends.
 *
 * <p>So what is asserted here is that the row remembers. Whether the consumer then continues
 * the trace is Spring Kafka's job and is configured rather than written, and the smoke check
 * proves that end against the running platform — a test with one process in it cannot.
 *
 * <p>Tracing is switched off in this suite, which makes the interesting assertion the negative
 * one: a service with no trace to record must write null rather than something that looks like
 * a trace id. A made up id would produce a trace of one span that looks like an answer.
 */
@SpringBootTest(properties = {
    "mizan.outbox.relay-every=3650d",
    "mizan.acquirer.timeout=2s"
})
class TheOutboxCarriesTheTraceTest extends MizanIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private dev.kauzes.mizan.common.web.outbox.Outbox outbox;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void theColumnIsThereAndTakesAWholeTraceParent() {
        // Written straight in, because what is being checked is the shape the database will
        // accept rather than anything the service decides.
        UUID id = UUID.randomUUID();
        String traceParent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        anEvent(id, traceParent);

        assertThat(jdbc.queryForObject(
                        "select trace_parent from outbox_event where id = ?", String.class, id))
                .isEqualTo(traceParent);
    }

    @Test
    void anythingThatIsNotATraceParentIsRefused() {
        // A malformed value is silently ignored by every consumer, and the loss shows up as a
        // trace that mysteriously stops at the topic — which is the thing this whole story is
        // about. Better to refuse the write.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> anEvent(UUID.randomUUID(), "not-a-trace-parent")))
                .as("the database should refuse a trace parent that is not one")
                .isNotNull();

        // Including one that is the right shape but the wrong case: the specification says
        // lowercase hexadecimal, and a consumer comparing ids would not match it.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> anEvent(
                                        UUID.randomUUID(),
                                        "00-4BF92F3577B34DA6A3CE929D0E0E4736-"
                                                + "00F067AA0BA902B7-01")))
                .isNotNull();
    }

    @Test
    void recordingAnEventWithNoTraceStoresNothingRatherThanSomethingInvented() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> outbox.record(
                new dev.kauzes.mizan.common.web.outbox.DomainEvent(
                        UUID.randomUUID(),
                        "payment.created",
                        1,
                        "payment",
                        paymentId,
                        merchantId,
                        java.time.Instant.now(),
                        "correlation-for-this-test",
                        Map.of("paymentId", paymentId.toString()))));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select correlation_id, trace_parent from outbox_event where aggregate_id = ?",
                paymentId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("correlation_id")).isEqualTo("correlation-for-this-test");
        assertThat(rows.get(0).get("trace_parent"))
                .as("nothing was tracing, so there is nothing to carry")
                .isNull();
    }

    private void anEvent(UUID id, String traceParent) {
        jdbc.update(
                """
                insert into outbox_event (id, type, version, aggregate_type, aggregate_id,
                    merchant_id, occurred_at, correlation_id, trace_parent, payload)
                values (?, 'payment.created', 1, 'payment', ?, ?, now(), 'c', ?, '{}'::jsonb)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                traceParent);
    }
}
