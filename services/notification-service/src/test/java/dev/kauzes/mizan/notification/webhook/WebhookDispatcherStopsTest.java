package dev.kauzes.mizan.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.kauzes.mizan.notification.NotificationMetrics;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A dispatcher told to stop finishes the deliveries it has already sent, and claims no more.
 *
 * <p>Nothing was ever lost without this — a claimed delivery is leased for five minutes and
 * retried after — but every rolling deploy spent an attempt on each cut-off call and could send a
 * merchant the same webhook twice. What is checked is the order that prevents both: the attempt in
 * flight completes and its outcome is written down before stopping returns, and a pass that comes
 * after the stop claims nothing.
 *
 * <p>No Spring and no database: this is about the order of three things in one object, and a
 * container would only add ways for the timing to be about something else.
 */
class WebhookDispatcherStopsTest {

    private final WebhookDeliveries deliveries = mock(WebhookDeliveries.class);
    private final WebhookSender sender = mock(WebhookSender.class);

    private final WebhookDispatcher dispatcher = new WebhookDispatcher(
            deliveries,
            sender,
            mock(NotificationMetrics.class),
            mock(PlatformTransactionManager.class),
            32,
            8);

    @Test
    void theDeliveryInFlightFinishesAndIsWrittenDownBeforeStoppingReturns() throws Exception {
        UUID id = UUID.randomUUID();
        WebhookDeliveries.Due due = mock(WebhookDeliveries.Due.class);
        when(due.id()).thenReturn(id);
        when(due.attempts()).thenReturn(1);
        when(deliveries.claim(anyInt())).thenReturn(List.of(due));

        CountDownLatch sending = new CountDownLatch(1);
        when(sender.send(any())).thenAnswer(invocation -> {
            sending.countDown();
            // A merchant endpoint that takes its time answering.
            Thread.sleep(800);
            return new WebhookSender.Outcome(true, 200, 800L, null);
        });

        Thread pass = Thread.ofPlatform().start(dispatcher::deliverWhatIsDue);
        assertThat(sending.await(5, TimeUnit.SECONDS)).as("the delivery should be under way").isTrue();

        // Told to stop while the merchant has not answered yet.
        dispatcher.finishWhatIsInFlight();

        // By the time stopping has returned, the answer arrived and was recorded — not dropped,
        // not left for the lease to expire and a second copy to be sent.
        // The status code is a primitive int on delivered(), so anyInt() rather than any(): the
        // latter matches with null, which cannot be unboxed into the argument.
        verify(deliveries).delivered(eq(id), anyInt(), anyInt(), anyLong());
        pass.join(5_000);
    }

    @Test
    void aPassAfterStoppingClaimsNothing() {
        dispatcher.finishWhatIsInFlight();

        dispatcher.deliverWhatIsDue();

        // Claiming increments the attempt and leases the row. Doing that on the way out would
        // strand the delivery for five minutes for nothing.
        verify(deliveries, times(0)).claim(anyInt());
    }
}
