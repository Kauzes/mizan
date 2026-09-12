package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.error.UnprocessableException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Reconciling a day, reading what is still outstanding, and ruling on it.
 *
 * <p>An actuator endpoint, like closing a day and paying a batch: whether this platform's
 * records match the bank's is a question about the platform rather than about one merchant's
 * data, and no merchant should be able to run it or to see another merchant's differences.
 *
 * <p>One view, shaped the way MIZ-50 and MIZ-53 settled on for dead lettered events and stuck
 * payments — what is outstanding, why, and an action to take — because it is the same question
 * about a different subject, and three operator views that answer it differently is two too
 * many. The action here is a ruling, and it is the only verb: nothing on this endpoint moves
 * money.
 */
@Component
@Endpoint(id = "reconciliation")
public class ReconciliationEndpoint {

    /** The only thing a ruling can be about, spelled out in the path so it reads as English. */
    private static final String DIFFERENCES = "differences";

    private final Reconciliation reconciliation;
    private final Rulings rulings;
    private final String currency;

    public ReconciliationEndpoint(
            Reconciliation reconciliation,
            Rulings rulings,
            @Value("${mizan.settlement.reconcile-currency:TRY}") String currency) {

        this.reconciliation = reconciliation;
        this.rulings = rulings;
        this.currency = currency;
    }

    /**
     * The queue: what is waiting for a person, how long it has been waiting, and what has been
     * decided lately.
     *
     * <p>Only differences nobody has ruled on. Outstanding is derived from whether anybody has
     * decided, never from whether the latest run still sees the difference — a difference that
     * stopped being reported because a later run did not notice it is a difference nobody
     * decided about, and each row says whether the newest run still found it.
     */
    @ReadOperation
    public Map<String, Object> whatIsOutstanding() {
        Map<String, Object> answer = new LinkedHashMap<>(rulings.howMuchIsWaiting());
        answer.put("differences", rulings.outstanding(500));
        answer.put("recentRulings", rulings.recent(20));
        return answer;
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

    /** Every ruling about one difference, oldest first, because the order is the story. */
    @ReadOperation
    public Map<String, Object> historyOf(@Selector String subject, @Selector String id) {
        onlyDifferences(subject);
        return Map.of("difference", id, "rulings", rulings.about(Rulings.idOf(id, "a difference")));
    }

    /**
     * Records what a person decided about one difference.
     *
     * <p>Two rulings and no more. {@code ACKNOWLEDGED} says a person has looked and explained
     * it, and changes nothing about the money. {@code CORRECTED} says an entry was posted in
     * the ledger, and names it — the entry is checked against the books before the ruling is
     * written, because a decision recorded as evidence has to be evidence.
     *
     * <p>What this endpoint deliberately cannot do is post that entry. Moving money on an
     * operator's say-so would be a way to write the books by hand, which is exactly what a
     * double entry ledger exists to make impossible; the correction goes through the ledger,
     * where it is an entry like any other and visible as a correction rather than a tidy-up.
     */
    @WriteOperation
    public Map<String, Object> rule(
            @Selector String subject,
            @Selector String id,
            // Optional to the framework, required by the domain. A ruling with nobody's name
            // on it has to come back saying why that is not good enough, and "Missing
            // parameters: why" is a sentence about a form rather than about an audit trail.
            @Nullable String ruling,
            @Nullable String ruledBy,
            @Nullable String why,
            @Nullable String correctedBy) {

        onlyDifferences(subject);
        return rulings.rule(
                Rulings.idOf(id, "a difference"),
                ruling,
                ruledBy,
                why,
                correctedBy == null || correctedBy.isBlank()
                        ? null
                        : Rulings.idOf(correctedBy, "an entry"));
    }

    private static void onlyDifferences(String subject) {
        if (!DIFFERENCES.equals(subject)) {
            throw new UnprocessableException(
                    "Only a difference can be ruled on: " + subject + " is not one.");
        }
    }
}
