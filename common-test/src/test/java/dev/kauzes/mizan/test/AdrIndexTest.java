package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every decision is in the index, and the index says what the decision says.
 *
 * <p>Sixty-three ADRs is past the point where anybody reads them all to find one, so there is an index —
 * and an index maintained by hand is an index missing the last three entries. It is written by
 * {@code ./scripts/adr-index.sh} from the ADRs themselves; this fails the build when the two disagree,
 * which is the only thing that keeps a generated file generated.
 *
 * <p>The columns are checked rather than the whole file, so adding a sentence to the index's own preamble
 * is not a test failure, while a decision missing from it, or listed with the wrong story or the wrong
 * status, is.
 */
class AdrIndexTest {

    private static final Pattern TITLE = Pattern.compile("^# ADR (\\d{4}): (.*)$");
    private static final Pattern FIELD = Pattern.compile("^- ([A-Za-z ]+):\\s*(.*)$");

    /** Header keys that say something later changed this decision. Kept in step with adr-index.sh. */
    private static final List<String> CHANGED =
            List.of("Superseded", "Superseded in part", "Narrowed", "Revisited", "Reversed");

    @Test
    void thereAreDecisionsToIndex() {
        assertThat(decisions()).hasSizeGreaterThan(50);
    }

    @Test
    void everyDecisionIsInTheIndex() {
        String index = index();
        decisions().forEach(adr -> assertThat(index)
                .as("ADR %s (%s) is not in docs/adr/README.md. Run ./scripts/adr-index.sh",
                        adr.number(), adr.file())
                .contains("| " + adr.number() + " | [" + adr.title() + "](" + adr.file() + ") |"));
    }

    @Test
    void eachEntrySaysWhatTheDecisionSays() {
        List<String> rows = index().lines().filter(line -> line.startsWith("| 0")).toList();
        decisions().forEach(adr -> {
            String row = rows.stream()
                    .filter(line -> line.startsWith("| " + adr.number() + " |"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row for ADR " + adr.number()));
            assertThat(row)
                    .as("the index's story and status for ADR %s", adr.number())
                    .contains("| " + adr.jira() + " |")
                    .contains("| " + adr.status() + " ");
        });
    }

    @Test
    void aDecisionSomethingLaterChangedSaysSoInTheIndex() {
        List<Decision> changed = decisions().stream()
                .filter(adr -> adr.changed().isPresent())
                .toList();
        assertThat(changed)
                .as("at least one decision here has been revisited, and the index is where that is found")
                .isNotEmpty();

        String index = index();
        changed.forEach(adr -> assertThat(index)
                .as("ADR %s was changed later, and the index must say so and why", adr.number())
                .contains(adr.changed().orElseThrow()));
    }

    @Test
    void theIndexPointsAtFilesThatExist() {
        Matcher links = Pattern.compile("\\]\\((\\d{4}-[^)]+\\.md)\\)").matcher(index());
        List<String> missing = new ArrayList<>();
        while (links.find()) {
            if (!Files.exists(adrFolder().resolve(links.group(1)))) {
                missing.add(links.group(1));
            }
        }
        assertThat(missing).as("the index links to files that are not there").isEmpty();
    }

    private record Decision(
            String number, String title, String file, String status, String jira, Optional<String> changed) {
    }

    private static String index() {
        return read(adrFolder().resolve("README.md"));
    }

    private static Path adrFolder() {
        return RepositoryRoot.path().resolve("docs/adr");
    }

    private static List<Decision> decisions() {
        List<Decision> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(adrFolder())) {
            files.sorted()
                    .filter(path -> path.getFileName().toString().matches("\\d{4}-.*\\.md"))
                    .filter(path -> !path.getFileName().toString().startsWith("0000-"))
                    .forEach(path -> found.add(decisionIn(path)));
        } catch (IOException couldNotList) {
            throw new IllegalStateException("could not list docs/adr", couldNotList);
        }
        return found;
    }

    private static Decision decisionIn(Path path) {
        String text = read(path);
        Matcher title = TITLE.matcher(text.lines().findFirst().orElse(""));
        if (!title.matches()) {
            throw new AssertionError(path.getFileName() + " does not start with '# ADR NNNN: a decision'");
        }

        String status = "";
        String jira = "";
        Optional<String> changed = Optional.empty();
        for (String line : text.split("\n## ", 2)[0].lines().toList()) {
            Matcher field = FIELD.matcher(line);
            if (!field.matches()) {
                continue;
            }
            String key = field.group(1).trim();
            String value = field.group(2).trim();
            if ("Status".equals(key)) {
                status = value;
            } else if ("Jira".equals(key)) {
                jira = value;
            } else if (CHANGED.contains(key)) {
                changed = Optional.of(key + ": " + value);
            }
        }
        return new Decision(
                title.group(1), title.group(2), path.getFileName().toString(), status, jira, changed);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException couldNotRead) {
            throw new IllegalStateException("could not read " + path, couldNotRead);
        }
    }
}
