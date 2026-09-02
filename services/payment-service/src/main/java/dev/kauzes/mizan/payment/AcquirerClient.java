package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * How this service talks to the acquirer.
 *
 * <p>The request carries the payment's own id as its identifier, which is what makes asking
 * again safe: an acquirer answers a repeated request with the decision it already made rather
 * than making a second one. So a retry after a lost answer cannot reserve the money twice.
 *
 * <p>A timeout is not a failure. It is the answer failing to arrive, which is a different
 * thing and is raised as such, because deciding a payment failed because we stopped listening
 * is how a customer is charged for something the merchant believes never happened.
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    private final RestClient http;

    public AcquirerClient(
            RestClient.Builder builder,
            @Value("${mizan.acquirer.base-url:http://localhost:8086}") String baseUrl,
            @Value("${mizan.acquirer.timeout:5s}") Duration timeout) {

        this.http = builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();
    }

    /** Asks the acquirer to reserve the money, using the payment's id as the request's. */
    public AcquirerDecision authorize(UUID paymentId, long amount, String currency, String card) {
        try {
            AcquirerResponse answer = http.post()
                    .uri("/acquirer/authorizations")
                    .body(new AcquirerRequest(paymentId.toString(), amount, currency, card))
                    .retrieve()
                    .body(AcquirerResponse.class);

            if (answer == null) {
                throw new MizanException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "The acquirer said nothing.");
            }
            return answer.asDecision();

        } catch (ResourceAccessException noAnswer) {
            // The request may well have been authorized. Nothing here is entitled to decide
            // that it was not; MIZ-44 is what asks.
            log.warn("no answer from the acquirer for payment {}", paymentId, noAnswer);
            throw new MizanException(
                    ErrorCode.UPSTREAM_TIMEOUT,
                    "The acquirer did not answer in time. Whether the payment was authorized "
                            + "is not yet known.",
                    noAnswer);
        } catch (MizanException already) {
            throw already;
        } catch (Exception unreachable) {
            log.error("could not reach the acquirer for payment {}", paymentId, unreachable);
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer could not be reached.",
                    unreachable);
        }
    }

    /** What the acquirer decided, in this service's own words. */
    public record AcquirerDecision(
            String acquirerReference,
            boolean approved,
            String reason,
            String cardLastFour,
            Instant decidedAt) {
    }

    private record AcquirerRequest(
            String requestId, long amount, String currency, String card) {

        /** A card number is not something to print, even here. */
        @Override
        public String toString() {
            return "AcquirerRequest[requestId=" + requestId + ", amount=" + amount + ", card=****]";
        }
    }

    private record AcquirerResponse(
            String acquirerReference,
            String requestId,
            String outcome,
            String reason,
            long amount,
            String currency,
            String cardLastFour,
            Instant decidedAt,
            String state) {

        AcquirerDecision asDecision() {
            return new AcquirerDecision(
                    acquirerReference,
                    "APPROVED".equals(outcome),
                    reason,
                    cardLastFour,
                    decidedAt);
        }
    }
}
