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

    /**
     * Takes the money the acquirer is holding.
     *
     * <p>Safe to send again. The acquirer answers a repeated capture with the capture it
     * already made, which is what lets this platform retry after a lost answer without taking
     * the money twice.
     */
    public void capture(String acquirerReference) {
        call("capture", acquirerReference);
    }

    /** Releases the money the acquirer is holding. Also safe to send again. */
    public void release(String acquirerReference) {
        call("void", acquirerReference);
    }

    private void call(String what, String acquirerReference) {
        try {
            http.post()
                    .uri("/acquirer/authorizations/{reference}/" + what, acquirerReference)
                    .retrieve()
                    .toBodilessEntity();

        } catch (ResourceAccessException noAnswer) {
            log.warn("no answer from the acquirer asking it to {} {}", what, acquirerReference);
            throw new MizanException(
                    ErrorCode.UPSTREAM_TIMEOUT,
                    "The acquirer did not answer in time. Whether the payment was "
                            + what.replace("void", "voided").replace("capture", "captured")
                            + " is not yet known.",
                    noAnswer);
        } catch (org.springframework.web.client.HttpClientErrorException refused) {
            // The acquirer disagrees about what this authorization is. Repeating is fine by
            // it, so this is a real contradiction rather than a retry, and is passed on as
            // one instead of being turned into a server error.
            log.warn("the acquirer refused to {} {}: {}", what, acquirerReference,
                    refused.getResponseBodyAsString());
            throw new MizanException(
                    ErrorCode.UNPROCESSABLE,
                    "The acquirer will not " + what + " this authorization.",
                    refused);
        } catch (MizanException already) {
            throw already;
        } catch (Exception unreachable) {
            log.error("could not reach the acquirer to {} {}", what, acquirerReference, unreachable);
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer could not be reached.",
                    unreachable);
        }
    }

    /**
     * Asks the acquirer what it did with a request, if anything.
     *
     * <p>Keyed on the payment's id, because that is what the request carried and what a
     * caller who never heard the answer still has. An empty answer is a real answer: this
     * acquirer has no record, so nothing was authorized.
     */
    public java.util.Optional<AcquirerDecision> lookUp(UUID paymentId) {
        try {
            return http.get()
                    .uri(builder -> builder
                            .path("/acquirer/authorizations")
                            .queryParam("requestId", paymentId.toString())
                            .build())
                    .exchange((request, response) -> {
                        // The status is read rather than the body, because a 404 here carries
                        // a problem detail and reading that as an authorization is how the
                        // first version of this turned "nothing happened" into an error.
                        if (response.getStatusCode().value() == 404) {
                            return java.util.Optional.<AcquirerDecision>empty();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new MizanException(
                                    ErrorCode.UPSTREAM_UNAVAILABLE,
                                    "The acquirer answered "
                                            + response.getStatusCode().value()
                                            + " when asked about this payment.");
                        }
                        return java.util.Optional.ofNullable(
                                        response.bodyTo(AcquirerResponse.class))
                                .map(AcquirerResponse::asDecision);
                    });

        } catch (MizanException already) {
            throw already;
        } catch (Exception unreachable) {
            // Not knowing whether we can ask is different from having asked and been told
            // nothing. The payment stays unresolved and the next sweep tries again.
            log.warn("could not ask the acquirer about payment {}", paymentId, unreachable);
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer could not be asked about this payment.",
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
