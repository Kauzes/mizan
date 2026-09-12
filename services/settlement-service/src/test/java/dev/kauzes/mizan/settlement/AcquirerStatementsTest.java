package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Reading somebody else's file, which is the part of reconciliation that is actually hard.
 *
 * <p>The test that matters is the trailer one. A statement truncated in transit looks exactly
 * like a day on which the bank settled less, and reconciling it would produce a page of
 * differences that are not differences at all — and somebody would start chasing them.
 */
class AcquirerStatementsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 3);

    private final AcquirerStatements statements = new AcquirerStatements(
            RestClient.builder(), "http://localhost:1", java.time.Duration.ofSeconds(1));

    @Test
    void readsAHeaderRowsAndATrailer() {
        AcquirerStatements.Statement statement = statements.read(
                DAY,
                "TRY",
                """
                H|MIZANBANK|2026-03-03|TRY
                D|acq_1|2026-03-03|10000|TRY|CAPTURE
                D|acq_2|2026-03-03|20000|TRY|CAPTURE
                T|2|30000
                """);

        assertThat(statement.settled()).hasSize(2);
        assertThat(statement.settled().getFirst().reference()).isEqualTo("acq_1");
        assertThat(statement.settled().getFirst().amount()).isEqualTo(10000L);
        assertThat(statement.rows()).isEqualTo(2);
        assertThat(statement.total()).isEqualTo(30000L);
    }

    @Test
    void refusesAFileThatDoesNotAddUpToItsOwnTrailer() {
        // The check that matters most. A bank may be wrong about this platform; it is not
        // wrong about itself, so a file that is is not a statement.
        assertThatThrownBy(() -> statements.read(
                        DAY,
                        "TRY",
                        """
                        H|MIZANBANK|2026-03-03|TRY
                        D|acq_1|2026-03-03|10000|TRY|CAPTURE
                        T|2|30000
                        """))
                .hasMessageContaining("not wrong about itself");
    }

    @Test
    void refusesAFileTruncatedMidWay() {
        // No trailer at all, which is what an interrupted download looks like.
        assertThatThrownBy(() -> statements.read(
                        DAY,
                        "TRY",
                        """
                        H|MIZANBANK|2026-03-03|TRY
                        D|acq_1|2026-03-03|10000|TRY|CAPTURE
                        """))
                .hasMessageContaining("no header, or no trailer");
    }

    @Test
    void refusesARecordTypeItDoesNotUnderstand() {
        // A format that has quietly gained a record type is a format this reader has quietly
        // stopped understanding, and guessing at the rest would be worse than stopping.
        assertThatThrownBy(() -> statements.read(
                        DAY,
                        "TRY",
                        """
                        H|MIZANBANK|2026-03-03|TRY
                        X|something-new|2026-03-03
                        D|acq_1|2026-03-03|10000|TRY|CAPTURE
                        T|1|10000
                        """))
                .hasMessageContaining("a record type this platform does not read: X");
    }

    @Test
    void refusesAnAmountThatIsNotANumber() {
        assertThatThrownBy(() -> statements.read(
                        DAY,
                        "TRY",
                        """
                        H|MIZANBANK|2026-03-03|TRY
                        D|acq_1|2026-03-03|ten thousand|TRY|CAPTURE
                        T|1|10000
                        """))
                .hasMessageContaining("a number that is not one");
    }

    @Test
    void readsADayOnWhichNothingSettled() {
        AcquirerStatements.Statement quiet = statements.read(
                DAY, "TRY", "H|MIZANBANK|2026-03-03|TRY\nT|0|0\n");

        // A quiet day is a real answer. It is an empty *file* that is indistinguishable from
        // a failed download, and that is refused elsewhere.
        assertThat(quiet.settled()).isEmpty();
        assertThat(quiet.total()).isZero();
    }

    @Test
    void ignoresBlankLinesBecauseFilesHaveThem() {
        AcquirerStatements.Statement statement = statements.read(
                DAY,
                "TRY",
                "H|MIZANBANK|2026-03-03|TRY\n\nD|acq_1|2026-03-03|10000|TRY|CAPTURE\n\nT|1|10000\n\n");

        assertThat(statement.settled()).hasSize(1);
    }
}
