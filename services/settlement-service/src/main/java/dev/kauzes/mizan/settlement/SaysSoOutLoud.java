package dev.kauzes.mizan.settlement;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Says out loud that differences are waiting, rather than waiting to be asked.
 *
 * <p>An endpoint only answers somebody who thought to look. The failure this guards against is
 * nobody looking: a reconciliation that found six differences on a Friday and an operator who
 * next opens the queue on Tuesday. So this says so on a timer, at a level that reaches
 * whatever is reading the logs, and it keeps saying so until a person has ruled on them —
 * because the thing worth alarming about is not that a difference exists, it is that nobody
 * has decided about it.
 *
 * <p>Louder once the oldest has gone unanswered for longer than the platform's patience. A
 * warning that never changes is a warning people learn to scroll past, and "this has been
 * waiting since Friday" is a different message from "this turned up a minute ago".
 *
 * <p>Deliberately not a health indicator. A difference between this platform and a bank is a
 * question for a person, not a reason to take a healthy service out of a load balancer — and a
 * service marked down for a bookkeeping difference is a service somebody restarts, which fixes
 * nothing and loses the queue nobody read.
 */
@Component
public class SaysSoOutLoud {

    private static final Logger log = LoggerFactory.getLogger(SaysSoOutLoud.class);

    private final Rulings rulings;
    private final Duration patience;

    public SaysSoOutLoud(
            Rulings rulings, @Value("${mizan.reconciliation.patience:24h}") Duration patience) {

        this.rulings = rulings;
        this.patience = patience;
    }

    @Scheduled(
            fixedDelayString = "${mizan.reconciliation.say-every:15m}",
            initialDelayString = "${mizan.reconciliation.say-first-after:1m}")
    public void sayWhatIsWaiting() {
        Map<String, Object> waiting = rulings.howMuchIsWaiting();
        long outstanding = ((Number) waiting.get("outstanding")).longValue();

        if (outstanding == 0) {
            // Said quietly, because a clean queue is worth being able to confirm afterwards
            // and is not worth interrupting anybody about.
            log.debug("no reconciliation differences are waiting for anybody");
            return;
        }

        Object oldest = waiting.get("oldest");
        Duration since = oldest == null
                ? Duration.ZERO
                : Duration.between(Instant.parse(oldest.toString()), Instant.now());

        if (since.compareTo(patience) > 0) {
            log.error(
                    "{} reconciliation difference(s) have been waiting for a person, the "
                            + "oldest for {} hour(s), which is longer than this platform's "
                            + "patience of {}: {}",
                    outstanding,
                    since.toHours(),
                    patience,
                    waiting.get("byOutcome"));
        } else {
            log.warn(
                    "{} reconciliation difference(s) are waiting for a person, the oldest for "
                            + "{} minute(s): {}",
                    outstanding,
                    since.toMinutes(),
                    waiting.get("byOutcome"));
        }
    }
}
