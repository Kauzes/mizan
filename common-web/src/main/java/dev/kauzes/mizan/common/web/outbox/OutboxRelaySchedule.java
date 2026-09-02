package dev.kauzes.mizan.common.web.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * What makes the relay run without anybody asking it to.
 *
 * <p>Separate from {@link OutboxRelay} so that a test can drain deliberately, one pass at a
 * time, and know exactly what has happened. A schedule inside the relay would mean every test
 * of it racing a timer.
 *
 * <p>A pass that publishes a full batch runs again immediately rather than waiting for the
 * next tick: a backlog is precisely when the delay between passes matters most, and it is the
 * one situation where the interval is the wrong thing to obey.
 */
public class OutboxRelaySchedule {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelaySchedule.class);

    /**
     * How many passes one tick will make before letting go.
     *
     * <p>Bounded so that an endless supply of events cannot keep a scheduler thread forever,
     * which would starve everything else scheduled on it.
     */
    private static final int PASSES = 10;

    private final OutboxRelay relay;

    public OutboxRelaySchedule(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${mizan.outbox.publish-every:1s}")
    public void publishWhatIsWaiting() {
        try {
            int published = 0;
            for (int pass = 0; pass < PASSES; pass++) {
                int sent = relay.drain();
                published += sent;
                if (sent == 0) {
                    break;
                }
            }
            if (published > 0) {
                log.debug("published {} event(s)", published);
            }
        } catch (RuntimeException failed) {
            // One bad pass must not stop the schedule. Individual events that will not publish
            // are already handled by the relay backing them off; this is for everything else,
            // such as the database being briefly unreachable.
            log.error("a pass of the outbox relay failed", failed);
        }
    }
}
