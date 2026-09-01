package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.ledger.journal.JournalRequests.PostEntryRequest;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostingRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * A digest of what a caller asked for, so that a reference sent twice with different contents
 * can be told from a genuine retry.
 *
 * <p>Kept rather than the request itself, because the only question ever asked of it is
 * whether this is the same request as last time, and a digest answers that without storing a
 * second copy of the books.
 *
 * <p>Postings are ordered before hashing. The same two postings in the other order are the
 * same movement of money, and a client that builds its list from a map should not be told its
 * retry is a different request.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(PostEntryRequest request) {
        String canonical = request.description().trim()
                + "\n"
                + request.occurredAt().toEpochMilli()
                + "\n"
                + (request.corrects() == null ? "" : request.corrects())
                + "\n"
                + request.postings().stream()
                        .sorted(Comparator.comparing((PostingRequest posting) ->
                                        posting.accountId().toString())
                                .thenComparingLong(PostingRequest::amount))
                        .map(posting -> posting.accountId() + ":" + posting.amount())
                        .collect(Collectors.joining(","));

        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
        }
    }
}
