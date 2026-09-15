package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * An accepted vulnerability is a decision with a reason and a deadline, not a place findings go.
 *
 * <p>Trivy enforces the deadline itself: past {@code expired_at} an entry stops suppressing
 * anything and the image scan fails again. What Trivy cannot enforce is that the deadline is
 * near and the reason is written down. Without that, the easy move is an entry that expires in
 * 2099 with no statement, which is a finding hidden rather than accepted.
 */
class ExceptionsTest {

    /** Long enough to wait for an upstream rebuild; short enough that somebody looks again. */
    private static final int LONGEST_ACCEPTANCE_DAYS = 90;

    private final List<Entry> entries = entriesIn(RepositoryRoot.read(".trivyignore.yaml"));

    @Test
    void everyExceptionSaysWhyAndWhoAccepted() {
        entries.forEach(entry -> assertThat(entry.statement())
                .as("%s is accepted without saying why, or without saying who accepted it",
                        entry.id())
                .hasSizeGreaterThan(80)
                .containsPattern("Accepted by \\S+ on \\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void everyExceptionEndsSoon() {
        LocalDate latest = LocalDate.now().plusDays(LONGEST_ACCEPTANCE_DAYS);
        entries.forEach(entry -> {
            assertThat(entry.expires())
                    .as("%s has no expiry, so it would suppress the finding forever", entry.id())
                    .isNotNull();
            assertThat(entry.expires())
                    .as("%s is accepted until %s, more than %d days away",
                            entry.id(), entry.expires(), LONGEST_ACCEPTANCE_DAYS)
                    .isBeforeOrEqualTo(latest);
        });
    }

    @Test
    void noExceptionIsScopedByAPathThatCanNeverMatch() {
        // An OS package reports no path, so a path filter on one matches nothing and the entry
        // suppresses nothing while looking exactly right. The first version of this file did
        // precisely that.
        assertThat(RepositoryRoot.read(".trivyignore.yaml")).doesNotContain("paths:");
    }

    private record Entry(String id, String statement, LocalDate expires) {
    }

    private static List<Entry> entriesIn(String file) {
        List<Entry> found = new ArrayList<>();
        String[] blocks = file.split("\\n\\s*- id: ");
        for (int at = 1; at < blocks.length; at++) {
            String block = blocks[at];
            String id = block.lines().findFirst().orElse("").trim();
            Matcher statement = Pattern.compile("statement: >-\\s*\\n((?:\\s{6,}.*\\n?)+)")
                    .matcher(block);
            Matcher expires = Pattern.compile("expired_at: (\\d{4}-\\d{2}-\\d{2})").matcher(block);
            found.add(new Entry(
                    id,
                    statement.find() ? statement.group(1).replaceAll("\\s+", " ").trim() : "",
                    expires.find() ? LocalDate.parse(expires.group(1)) : null));
        }
        assertThat(found).as("the exceptions file should have been read").isNotEmpty();
        return found;
    }
}
