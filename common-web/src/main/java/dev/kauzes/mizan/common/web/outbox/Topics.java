package dev.kauzes.mizan.common.web.outbox;

/**
 * What a topic is called, decided in one place.
 *
 * <p>A topic name is a published contract, in the same way an endpoint's path is: consumers
 * are configured with it, and renaming one is a migration rather than an edit. So it is
 * derived from the aggregate rather than typed at a call site, and a producer cannot invent a
 * name that no consumer is listening to.
 *
 * <p>One topic per aggregate type, not per event type. Every event about one payment then
 * lands on one topic with one key, which is what makes ordering per payment possible at all —
 * spread across {@code payment.authorized} and {@code payment.captured} they would be in
 * different partitions of different topics and their order would mean nothing. It also means
 * a consumer that wants everything about payments subscribes once, and one that wants a
 * single type filters on the envelope, which is cheap.
 *
 * <p>No version in the name. The payload's version is on the envelope, where a consumer can
 * read it and say "I do not understand version 3" rather than silently receiving nothing
 * because it is subscribed to a topic that stopped being written to.
 */
public final class Topics {

    private static final String PREFIX = "mizan.";
    private static final String SUFFIX = ".events";

    private Topics() {
    }

    /** For example, {@code payment} becomes {@code mizan.payment.events}. */
    public static String of(String aggregateType) {
        return PREFIX + aggregateType + SUFFIX;
    }
}
