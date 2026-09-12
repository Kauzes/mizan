package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.identity.ServiceCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * How a settlement reaches the books.
 *
 * <p>Two movements, at two different moments, because they are two different facts.
 *
 * <p>Taking the fee happens when a batch closes: the platform owes the merchant less, and the
 * platform has earned that much. The merchant's settlement account is debited and the
 * platform's fee account is credited. Revenue that is not written down is revenue nobody can
 * reconcile, which is why the fee has an account rather than simply being subtracted.
 *
 * <p>Paying the merchant happens when the money is actually sent: the platform owes them
 * nothing more for that batch, and holds that much less at the acquirer. The merchant's
 * settlement account is debited again and the platform's clearing account is credited.
 *
 * <p>Neither adjusts a balance. Both are entries whose postings sum to zero, because a balance
 * that can be adjusted is a balance nobody can audit — which is the whole reason this platform
 * has a double entry ledger rather than a column.
 *
 * <p>Nothing here fails open. A capture can be recorded late and the money is still taken, but
 * a payout without an entry is money leaving the platform with nothing to say it did. So when
 * the ledger cannot be reached, the payout does not happen and is tried again.
 */
@Component
public class LedgerBooks {

    private static final Logger log = LoggerFactory.getLogger(LedgerBooks.class);

    /** What the platform owes one merchant, in their own books. */
    private static final String SETTLEMENT = "settlement.";

    /** What the platform has earned. Seeded by a migration, like every platform account. */
    private static final String FEES = "platform.fees.";

    /** Where the money sits between the acquirer taking it and a payout sending it on. */
    private static final String CLEARING = "platform.clearing.";

    private final RestClient http;

    public LedgerBooks(
            RestClient.Builder builder,
            @Value("${mizan.ledger.base-url:http://localhost:8082}") String baseUrl,
            @Value("${mizan.ledger.timeout:5s}") Duration timeout,
            @Value("${mizan.internal.service-token:}") String serviceToken) {

        if (serviceToken.isBlank()) {
            throw new IllegalStateException(
                    "mizan.internal.service-token is not set. Without it this service cannot "
                            + "record a settlement, and money must not move on this platform "
                            + "without an entry that says it did.");
        }

        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(ServiceCredential.HEADER, serviceToken)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();
    }

    /**
     * Records that the platform has earned its fee on a batch.
     *
     * <p>The reference is derived from the batch, so posting it twice writes one entry and the
     * second attempt is answered with the first one's. That is what makes the sweep that
     * finishes unrecorded batches safe to run as often as it likes.
     */
    public UUID recordFee(UUID merchantId, UUID batchId, long fee, String currencyCode) {
        if (fee <= 0) {
            throw new IllegalArgumentException("there is nothing to record for a fee of " + fee);
        }
        String currency = currencyCode.toLowerCase(Locale.ROOT);

        return post(new Entry(
                merchantId,
                "settlement:" + batchId + ":fee",
                "Settlement fee",
                Instant.now(),
                null,
                List.of(
                        // Positive is a debit. The platform owes this merchant less...
                        new Posting(SETTLEMENT + currency, fee),
                        // ...and has earned that much.
                        new Posting(FEES + currency, -fee))));
    }

    /**
     * Records that the merchant has been paid what the batch left them.
     *
     * <p>The opposite shape to a capture: the platform owes less and holds less. Nothing here
     * moves money on its own — the ledger records what happened, and a real deployment's
     * transfer to a merchant's bank is what this entry is about.
     */
    public UUID recordPayout(UUID merchantId, UUID batchId, long net, String currencyCode) {
        if (net <= 0) {
            throw new IllegalArgumentException("there is nothing to pay out for " + net);
        }
        String currency = currencyCode.toLowerCase(Locale.ROOT);

        return post(new Entry(
                merchantId,
                "settlement:" + batchId + ":payout",
                "Paid out to the merchant",
                Instant.now(),
                null,
                List.of(
                        // The platform owes this merchant nothing more for this batch...
                        new Posting(SETTLEMENT + currency, net),
                        // ...and holds that much less at the acquirer.
                        new Posting(CLEARING + currency, -net))));
    }

    /**
     * What the platform owes this merchant, according to the books.
     *
     * <p>Asked of the ledger rather than worked out from this service's own batches, because
     * the batches are not the whole story: a refund reduces what is owed and belongs to the
     * payment service, and a settlement service that added up only its own rows would
     * confidently pay out money that had already gone back to a customer.
     *
     * <p>A liability is credit positive, so what is owed is the negative of the balance. The
     * sign is not flipped by the ledger on the way out — the type says which way to read it —
     * so it is flipped here, once, with a name that says which direction it now points.
     */
    public long owedTo(UUID merchantId, String currencyCode) {
        String currency = currencyCode.toLowerCase(Locale.ROOT);
        try {
            Held held = http.get()
                    .uri("/internal/balances/{merchantId}/{code}", merchantId, SETTLEMENT + currency)
                    .retrieve()
                    .body(Held.class);

            if (held == null) {
                throw new MizanException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger said nothing.");
            }
            return -held.balance();

        } catch (HttpClientErrorException refused) {
            throw new MizanException(
                    ErrorCode.UNPROCESSABLE,
                    "The books cannot say what is owed: " + detailOf(refused),
                    refused);
        } catch (MizanException already) {
            throw already;
        } catch (Exception unreachable) {
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The ledger could not be reached.",
                    unreachable);
        }
    }

    private UUID post(Entry entry) {
        try {
            Written written =
                    http.post().uri("/internal/entries").body(entry).retrieve().body(Written.class);

            if (written == null || written.id() == null) {
                throw new MizanException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger said nothing.");
            }
            log.info("recorded {} as entry {}", entry.externalReference(), written.id());
            return written.id();

        } catch (ResourceAccessException noAnswer) {
            // The entry may well have been written. Nothing here decides that it was not: the
            // reference makes sending it again safe, and sending it again is the answer.
            log.warn("no answer from the ledger for {}", entry.externalReference(), noAnswer);
            throw new MizanException(
                    ErrorCode.UPSTREAM_TIMEOUT,
                    "The ledger did not answer in time. Nothing has been paid; send this "
                            + "again to finish recording it.",
                    noAnswer);

        } catch (HttpClientErrorException refused) {
            // Passed on as it stands, because the reason is almost always something an
            // operator has to act on — a merchant who never opened a settlement account — and
            // turning it into "internal error" would hide the one useful sentence.
            log.error(
                    "the ledger refused {}: {}",
                    entry.externalReference(),
                    refused.getResponseBodyAsString());
            throw new MizanException(
                    ErrorCode.UNPROCESSABLE,
                    "The books would not accept this movement: " + detailOf(refused),
                    refused);

        } catch (MizanException already) {
            throw already;
        } catch (Exception unreachable) {
            log.error("could not reach the ledger for {}", entry.externalReference(), unreachable);
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The ledger could not be reached.",
                    unreachable);
        }
    }

    /** The ledger's own sentence, if it sent one, rather than this service's guess at it. */
    private static String detailOf(HttpClientErrorException refused) {
        org.springframework.http.ProblemDetail problem =
                refused.getResponseBodyAs(org.springframework.http.ProblemDetail.class);
        return problem == null || problem.getDetail() == null
                ? "the ledger answered " + refused.getStatusCode().value()
                : problem.getDetail();
    }

    private record Posting(String accountCode, long amount) {
    }

    private record Entry(
            UUID merchantId,
            String externalReference,
            String description,
            Instant occurredAt,
            UUID corrects,
            List<Posting> postings) {
    }

    private record Written(UUID id) {
    }

    private record Held(long balance) {
    }
}
