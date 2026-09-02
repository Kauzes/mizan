package dev.kauzes.mizan.payment;

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
 * How a captured payment reaches the books.
 *
 * <p>Two accounts move. The platform's clearing account is debited, because the platform now
 * holds that money at the acquirer, and the merchant's settlement account is credited, because
 * the platform now owes it to them. The two are equal and opposite, which is the only thing
 * that makes it an entry rather than an assertion.
 *
 * <p>A merchant may not write an entry like that. It names an account they do not own, and an
 * endpoint that let them name it would let them credit themselves out of the account every
 * merchant's money passes through. So this goes to a route that is not a merchant route, with
 * a credential no merchant has.
 *
 * <p>The entry carries the payment's own id as its reference, so a capture that is sent twice
 * writes one entry and the second call is answered with the first one's. That is what makes a
 * capture whose answer was lost safe to repeat.
 */
@Component
public class LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(LedgerClient.class);

    /** Where the money sits between the acquirer taking it and settlement paying it out. */
    private static final String CLEARING = "platform.clearing.";

    /**
     * What the platform owes one merchant, in their books.
     *
     * <p>A code rather than an id: the ids belong to a migration in another service's
     * database, and this service should not be holding them. The ledger resolves it, and
     * refuses if the merchant never opened it rather than opening one on their behalf.
     */
    private static final String SETTLEMENT = "settlement.";

    private final RestClient http;

    public LedgerClient(
            RestClient.Builder builder,
            @Value("${mizan.ledger.base-url:http://localhost:8082}") String baseUrl,
            @Value("${mizan.ledger.timeout:5s}") Duration timeout,
            @Value("${mizan.internal.service-token:}") String serviceToken) {

        if (serviceToken.isBlank()) {
            throw new IllegalStateException(
                    "mizan.internal.service-token is not set. Without it this service cannot "
                            + "record a capture, and a capture that cannot be recorded must not "
                            + "be one that quietly happens anyway.");
        }

        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(ServiceCredential.HEADER, serviceToken)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();
    }

    /**
     * Records that money has moved, and returns the entry that says so.
     *
     * @return the id of the entry, whether this call wrote it or an earlier one did
     */
    public UUID recordCapture(UUID merchantId, Payment payment) {
        String currency = payment.money().currency().getCurrencyCode().toLowerCase(Locale.ROOT);
        long amount = payment.money().amount();

        Entry entry = new Entry(
                merchantId,
                "payment:" + payment.id() + ":capture",
                "Card payment captured, " + payment.reference(),
                Instant.now(),
                List.of(
                        // Positive is a debit. The platform holds more at the acquirer...
                        new Posting(CLEARING + currency, amount),
                        // ...and owes the merchant more. The two sum to zero, which is what
                        // the ledger checks before it writes anything.
                        new Posting(SETTLEMENT + currency, -amount)));

        return post(entry);
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
            // The entry may well have been written. Nothing here decides that it was not:
            // the reference makes sending it again safe, and sending it again is the answer.
            log.warn("no answer from the ledger for {}", entry.externalReference(), noAnswer);
            throw new MizanException(
                    ErrorCode.UPSTREAM_TIMEOUT,
                    "The ledger did not answer in time. The money has been taken; send this "
                            + "capture again to finish recording it.",
                    noAnswer);

        } catch (HttpClientErrorException refused) {
            // The ledger will not write this. Passed on as it stands, because the reason is
            // almost always something an operator has to act on — an account nobody opened —
            // and turning it into "internal error" would hide the one useful sentence.
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
                    ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger could not be reached.", unreachable);
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
            List<Posting> postings) {
    }

    private record Written(UUID id) {
    }
}
