package dev.kauzes.mizan.payment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * What needs a person, and the two things a person can do about it.
 *
 * <p>An actuator endpoint rather than an API route, for the same reason the ledger's integrity
 * check and the dead letter list are: this is a question about the platform rather than about
 * one merchant's data, and there is no merchant who should be asking it. Reachable only through
 * the gateway's internal route, which needs a token.
 *
 * <p>The shape is deliberately the one MIZ-50 settled on for dead lettered events — what is
 * outstanding, why, and an action to take — because it is the same question about a different
 * subject, and two operator views that answer it differently is one view too many.
 */
@Component
@Endpoint(id = "stuck")
public class StuckEndpoint {

    private final StuckPayments stuck;
    private final OperatorDecisions decisions;

    public StuckEndpoint(StuckPayments stuck, OperatorDecisions decisions) {
        this.stuck = stuck;
        this.decisions = decisions;
    }

    @ReadOperation
    public Map<String, Object> whatIsStuck() {
        List<StuckPayments.Stuck> everything = stuck.everything();
        return Map.of(
                "total", everything.size(),
                "stuck", everything,
                "recentDecisions", decisions.recent(20));
    }

    /**
     * Retries one, or records that a person has dealt with it.
     *
     * <p>Two verbs and no more. Anything that moved money on an operator's say-so would be a
     * way to write the books by hand, which is exactly what a double entry ledger exists to
     * make impossible; an operator who genuinely needs that posts a correcting entry through
     * the ledger, where it is an entry like any other and just as visible.
     */
    @WriteOperation
    public Map<String, Object> decide(
            @Selector String kind,
            @Selector String id,
            String decision,
            String decidedBy,
            String why) {

        if (decidedBy == null || decidedBy.isBlank() || why == null || why.isBlank()) {
            return Map.of(
                    "error",
                    "decidedBy and why are both required: a decision nobody owns and nobody "
                            + "explained is not an audit trail");
        }

        UUID subject = UUID.fromString(id);
        return switch (decision == null ? "" : decision.toUpperCase(java.util.Locale.ROOT)) {
            case "RETRY" -> stuck.retry(kind.toUpperCase(java.util.Locale.ROOT), subject,
                    decidedBy, why);
            case "CLOSED" -> stuck.close(kind.toUpperCase(java.util.Locale.ROOT), subject,
                    decidedBy, why);
            default -> Map.of("error", "decision must be RETRY or CLOSED");
        };
    }
}
