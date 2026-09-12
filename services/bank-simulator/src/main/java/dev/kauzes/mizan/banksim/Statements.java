package dev.kauzes.mizan.banksim;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What this acquirer says it settled, and where it disagrees with the platform on purpose.
 *
 * <p>A statement that always agreed would prove nothing. Reconciliation exists because real
 * acquirers and real platforms disagree — a transaction one has and the other does not, an
 * amount that differs by a minor unit, a settlement that lands a day later — and a simulator
 * that could not produce those would let a reconciliation job be written that finds nothing
 * and passes every test.
 *
 * <p>So this produces three disagreements, deliberately and deterministically:
 *
 * <ul>
 *   <li>one transaction the platform has and this statement does not,
 *   <li>one this statement has and the platform never issued,
 *   <li>and one both have, for amounts that differ.
 * </ul>
 *
 * <p>Deterministically, because a reconciliation test that depends on chance is a test that
 * fails on Tuesdays. Which transaction gets which treatment follows from sorting them by
 * reference, so the same day asked about twice answers the same way.
 *
 * <p>The format is a pipe delimited file with a header and a trailer, which is what a real
 * acquirer sends and deliberately not this platform's own JSON. Reading somebody else's
 * format, and checking their trailer against their own detail rows, is the part of
 * reconciliation that is actually hard.
 */
@Component
public class Statements {

    /** What a phantom transaction is called, so a test can recognise one by sight. */
    static final String NEVER_ISSUED = "acq_never_issued_";

    /** How far out the mismatched amount is: one minor unit, which is the hardest case. */
    static final long OFF_BY = 1L;

    /** What the phantom transaction is for. A round number nobody would take by accident. */
    static final long PHANTOM_AMOUNT = 4_200L;

    private final Acquirer acquirer;

    /**
     * The zone this acquirer's day ends in.
     *
     * <p>Its own, not the platform's, even though they are configured the same here. A real
     * acquirer's cut-off is not negotiable and a platform that assumed otherwise would
     * reconcile the wrong day — which is a difference worth being able to produce later.
     */
    private final ZoneId zone;

    public Statements(
            Acquirer acquirer, @Value("${mizan.acquirer.statement-zone:Europe/Istanbul}") String zone) {

        this.acquirer = acquirer;
        this.zone = ZoneId.of(zone);
    }

    /**
     * The statement for one day in one currency.
     *
     * @param faithful when true, exactly what this acquirer settled, with no disagreements.
     *     For the caller who wants to prove that reconciliation finds nothing when there is
     *     nothing to find, which is as much a test as the other way round.
     */
    public String forDay(LocalDate day, String currency, boolean faithful) {
        List<Line> settled = new ArrayList<>(
                acquirer.settledOn(day, currency, zone).stream()
                        .map(authorization -> new Line(
                                authorization.acquirerReference(),
                                authorization.remaining(),
                                currency))
                        // By reference, so which transaction gets which treatment below is a
                        // fact about the data rather than about the order a map happened to
                        // be in.
                        .sorted(Comparator.comparing(Line::reference))
                        .toList());

        List<Line> lines = faithful ? settled : disagreeing(settled, day);

        StringBuilder file = new StringBuilder();
        file.append("H|MIZANBANK|").append(day).append('|').append(currency).append('\n');
        long total = 0;
        for (Line line : lines) {
            file.append("D|")
                    .append(line.reference())
                    .append('|')
                    .append(day)
                    .append('|')
                    .append(line.amount())
                    .append('|')
                    .append(line.currency())
                    .append("|CAPTURE\n");
            total += line.amount();
        }
        // A trailer with a count and a total, which is what makes a truncated file detectable.
        // Consistent with the detail rows above it even when those disagree with the
        // platform: this acquirer is wrong about the platform, not about itself.
        file.append("T|").append(lines.size()).append('|').append(total).append('\n');

        return file.toString();
    }

    /**
     * The same day, with the three disagreements applied.
     *
     * <p>What each one does depends on how much there is to work with, and the rule is written
     * out rather than left to an off-by-one: with two or more transactions the first goes
     * missing and the last is off by a minor unit, with one it is only off by a minor unit,
     * and a phantom is always added because inventing one needs nothing to work with.
     */
    private List<Line> disagreeing(List<Line> settled, LocalDate day) {
        List<Line> lines = new ArrayList<>(settled);

        if (lines.size() >= 2) {
            // One the platform has and this statement does not.
            lines.removeFirst();
        }
        if (!lines.isEmpty()) {
            // One both have, for amounts that differ.
            Line last = lines.removeLast();
            lines.add(new Line(last.reference(), last.amount() - OFF_BY, last.currency()));
        }

        // And one this statement has that the platform never issued. Named after the day, so
        // two days' statements do not both claim the same phantom.
        lines.add(new Line(
                NEVER_ISSUED + day.toString().replace("-", ""),
                PHANTOM_AMOUNT,
                lines.isEmpty() ? "TRY" : lines.getFirst().currency()));

        return List.copyOf(lines);
    }

    private record Line(String reference, long amount, String currency) {
    }
}
