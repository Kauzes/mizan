package dev.kauzes.mizan.settlement;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Reconciling a day, and reading what is still outstanding.
 *
 * <p>An actuator endpoint, like closing a day and paying a batch: whether this platform's
 * records match the bank's is a question about the platform rather than about one merchant's
 * data, and no merchant should be able to run it or to see another merchant's differences.
 */
@Component
@Endpoint(id = "reconciliation")
public class ReconciliationEndpoint {

    private final Reconciliation reconciliation;
    private final String currency;

    public ReconciliationEndpoint(
            Reconciliation reconciliation,
            @Value("${mizan.settlement.reconcile-currency:TRY}") String currency) {

        this.reconciliation = reconciliation;
        this.currency = currency;
    }

    @ReadOperation
    public Map<String, Object> whatIsOutstanding() {
        return Map.of("differences", reconciliation.differences(500));
    }

    /**
     * Reconciles one day against the bank's statement.
     *
     * <p>Repeatable, and the same day says the same thing twice: each run is its own record
     * because it happened, and the differences it finds are the rows the first run made rather
     * than a second set. The only time anybody reconciles twice is after an incident, which is
     * the worst time to be handed a page of duplicates.
     */
    @WriteOperation
    public Map<String, Object> reconcile(@Selector String day) {
        Reconciliation.Found found = reconciliation.reconcile(LocalDate.parse(day), currency);
        return found.asAnswer();
    }

    /** Every run for a day, so a re-run can be compared with the one before it. */
    @ReadOperation
    public Map<String, Object> runsFor(@Selector String day) {
        return Map.of(
                "day", day,
                "runs", reconciliation.runsFor(LocalDate.parse(day)));
    }
}
