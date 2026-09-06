package dev.kauzes.mizan.notification.webhook;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes the deliveries that are due, and lets a slow merchant slow only themselves.
 *
 * <p>That last part is the requirement this design exists to satisfy, and three things
 * together are what earn it:
 *
 * <ul>
 *   <li>work is claimed with {@code for update skip locked}, so a worker holding a delivery
 *       to an endpoint that never answers holds one row and everybody else steps over it;
 *   <li>the claim commits before the call is made, so a database transaction is never open
 *       across a request to somebody else's server for as long as they feel like taking;
 *   <li>every call has a timeout, so one endpoint occupies one worker for seconds rather than
 *       for as long as it likes.
 * </ul>
 *
 * <p>Take any one of those away and one broken merchant stops this platform telling anybody
 * anything, which is the failure mode this story is named after.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookDeliveries deliveries;
    private final WebhookSender sender;
    private final TransactionTemplate transaction;
    private final ExecutorService workers;
    private final int batchSize;
    private final int attemptLimit;

    public WebhookDispatcher(
            WebhookDeliveries deliveries,
            WebhookSender sender,
            PlatformTransactionManager transactions,
            @Value("${mizan.webhooks.batch-size:32}") int batchSize,
            @Value("${mizan.webhooks.attempts:8}") int attemptLimit) {

        this.deliveries = deliveries;
        this.sender = sender;
        this.transaction = new TransactionTemplate(transactions);
        this.batchSize = batchSize;
        this.attemptLimit = attemptLimit;
        // A virtual thread per attempt, and no pool size to configure. These spend nearly all
        // their time waiting on somebody else's server, which is exactly the shape virtual
        // threads are for; a fixed pool would make "how many merchants can we talk to at
        // once" a number somebody has to guess, and guessing it low is how one slow endpoint
        // starts delaying everybody after all. The batch size bounds it instead, and means
        // something: how much work one pass takes on.
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("webhook-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${mizan.webhooks.deliver-every:2s}")
    public void deliverWhatIsDue() {
        try {
            // Claimed in a transaction that ends here, before any HTTP happens.
            List<WebhookDeliveries.Due> due =
                    transaction.execute(status -> deliveries.claim(batchSize));

            if (due == null || due.isEmpty()) {
                return;
            }

            log.debug("delivering {} webhook(s)", due.size());
            List<java.util.concurrent.Future<?>> inFlight = new java.util.ArrayList<>();
            for (WebhookDeliveries.Due delivery : due) {
                inFlight.add(workers.submit(() -> attempt(delivery)));
            }

            // Waited for so that one pass does not start before the last has finished and
            // pile attempt on attempt. The timeout on each call bounds how long this can be.
            for (var future : inFlight) {
                future.get(2, TimeUnit.MINUTES);
            }

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unexpected) {
            // One bad pass must not stop the schedule. Individual deliveries that fail are
            // already recorded and backed off; this is for everything else.
            log.error("a pass of the webhook dispatcher failed", unexpected);
        }
    }

    /** One delivery, with its outcome written down whatever it was. */
    private void attempt(WebhookDeliveries.Due delivery) {
        WebhookSender.Outcome outcome = sender.send(delivery);

        transaction.executeWithoutResult(status -> {
            if (outcome.delivered()) {
                deliveries.delivered(
                        delivery.id(),
                        delivery.attempts(),
                        outcome.statusCode(),
                        outcome.tookMillis());
            } else {
                deliveries.failed(
                        delivery.id(),
                        delivery.attempts(),
                        outcome.statusCode(),
                        outcome.tookMillis(),
                        outcome.error(),
                        attemptLimit);
            }
        });
    }
}
