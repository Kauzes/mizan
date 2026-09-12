package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reading the bank's statement.
 *
 * <p>Somebody else's format, which is the part of reconciliation that is actually hard. A
 * pipe delimited file with a header, a row per transaction and a trailer carrying a count and
 * a total.
 *
 * <p>The trailer is checked against the rows, and a file that disagrees with itself is refused
 * rather than reconciled. That check is the one thing here that matters most: a statement
 * truncated in transit looks exactly like a day on which the bank settled less, and
 * reconciling it would produce a page of differences that are not differences at all. A bank
 * may be wrong about this platform; it is not wrong about itself, and a file that is is not a
 * statement.
 */
@Component
public class AcquirerStatements {

    private static final Logger log = LoggerFactory.getLogger(AcquirerStatements.class);

    private final RestClient http;

    public AcquirerStatements(
            RestClient.Builder builder,
            @Value("${mizan.acquirer.base-url:http://localhost:8086}") String baseUrl,
            @Value("${mizan.acquirer.timeout:10s}") Duration timeout) {

        this.http = builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();
    }

    /** One transaction, as the bank says it settled. */
    public record Settled(String reference, long amount, String currency) {
    }

    /**
     * A day's statement, read and checked.
     *
     * @param rows what the trailer said there were, which is not assumed to be what arrived
     * @param total what the trailer said they came to
     */
    public record Statement(
            LocalDate day, String currency, List<Settled> settled, int rows, long total) {

        public Statement {
            settled = List.copyOf(settled);
        }
    }

    public Statement forDay(LocalDate day, String currency) {
        String file;
        try {
            file = http.get()
                    .uri("/statements/{day}?currency={currency}", day, currency)
                    .retrieve()
                    .body(String.class);

        } catch (RuntimeException unreachable) {
            log.warn("the acquirer's statement for {} could not be fetched", day, unreachable);
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer's statement for " + day + " could not be fetched.",
                    unreachable);
        }

        if (file == null || file.isBlank()) {
            throw new MizanException(
                    ErrorCode.UNPROCESSABLE,
                    "The acquirer sent an empty statement for " + day + ". An empty file and a "
                            + "failed download look the same, so this is not reconciled.");
        }

        return read(day, currency, file);
    }

    /**
     * Reads the file, and refuses one that does not add up to its own trailer.
     *
     * <p>Line by line rather than by splitting on everything at once, because a line this
     * platform does not recognise is worth naming: a format that has quietly gained a record
     * type is a format this reader has quietly stopped understanding.
     */
    Statement read(LocalDate day, String currency, String file) {
        List<Settled> settled = new ArrayList<>();
        Integer claimedRows = null;
        Long claimedTotal = null;
        String header = null;

        for (String line : file.split("\n")) {
            String row = line.strip();
            if (row.isEmpty()) {
                continue;
            }
            String[] fields = row.split("\\|");

            switch (fields[0]) {
                case "H" -> header = row;
                case "D" -> {
                    if (fields.length < 5) {
                        throw refuse(day, "a detail row with only " + fields.length + " fields");
                    }
                    settled.add(new Settled(fields[1], parse(day, fields[3]), fields[4]));
                }
                case "T" -> {
                    if (fields.length < 3) {
                        throw refuse(day, "a trailer with only " + fields.length + " fields");
                    }
                    claimedRows = (int) parse(day, fields[1]);
                    claimedTotal = parse(day, fields[2]);
                }
                default -> throw refuse(day, "a record type this platform does not read: " + fields[0]);
            }
        }

        if (header == null || claimedRows == null) {
            throw refuse(day, "no header, or no trailer");
        }

        long summed = settled.stream().mapToLong(Settled::amount).sum();
        if (claimedRows != settled.size() || claimedTotal != summed) {
            // The check that matters most. A truncated file looks exactly like a quiet day.
            throw refuse(
                    day,
                    "a trailer claiming "
                            + claimedRows
                            + " rows totalling "
                            + claimedTotal
                            + " over "
                            + settled.size()
                            + " rows totalling "
                            + summed
                            + ". A bank may be wrong about this platform; it is not wrong "
                            + "about itself, so this file is not trusted");
        }

        log.info("read the acquirer's statement for {}: {} row(s), {}", day, claimedRows, summed);
        return new Statement(day, currency, settled, claimedRows, claimedTotal);
    }

    private static long parse(LocalDate day, String field) {
        try {
            return Long.parseLong(field.strip());
        } catch (NumberFormatException notANumber) {
            throw refuse(day, "a number that is not one: " + field);
        }
    }

    private static MizanException refuse(LocalDate day, String what) {
        return new MizanException(
                ErrorCode.UNPROCESSABLE,
                "The acquirer's statement for " + day + " has " + what + ".");
    }
}
