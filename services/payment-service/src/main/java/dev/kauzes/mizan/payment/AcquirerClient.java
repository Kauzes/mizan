package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.web.Bulkhead;
import dev.kauzes.mizan.common.web.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
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
 * How this service talks to the acquirer.
 *
 * <p>The request carries the payment's own id as its identifier, which is what makes asking
 * again safe: an acquirer answers a repeated request with the decision it already made rather
 * than making a second one. So a retry after a lost answer cannot reserve the money twice.
 *
 * <p>A timeout is not a failure. It is the answer failing to arrive, which is a different
 * thing and is raised as such, because deciding a payment failed because we stopped listening
 * is how a customer is charged for something the merchant believes never happened.
 *
 * <h2>What a slow or broken acquirer is allowed to cost</h2>
 *
 * <p>ADR 0052. Every call waits behind two guards, and both refuse <em>without sending</em>,
 * which is the one kind of failure that leaves nothing unknown: a payment whose authorization
 * was never sent was not authorized, and a capture never sent took nothing.
 *
 * <ul>
 *   <li><b>A bulkhead</b>: only so many calls may wait on the acquirer at once. Each one holds a
 *       request thread and a database connection, so without a limit a slow acquirer takes every
 *       connection, and a merchant merely reading a payment waits behind the bank.
 *   <li><b>A breaker</b>: after enough failures in a row the acquirer is left alone for a while,
 *       so an outage costs a handful of timeouts rather than one per payment. A refusal is an
 *       answer and never counts; neither does a decline, which arrives as a success.
 * </ul>
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    private final RestClient http;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Counter notSentBecauseOpen;
    private final Counter notSentBecauseFull;

    public AcquirerClient(
            RestClient.Builder builder,
            MeterRegistry meters,
            @Value("${mizan.acquirer.base-url:http://localhost:8086}") String baseUrl,
            @Value("${mizan.acquirer.timeout:5s}") Duration timeout,
            @Value("${mizan.acquirer.failures-before-giving-up:10}") int failuresBeforeGivingUp,
            @Value("${mizan.acquirer.leave-alone-for:10s}") Duration leaveAloneFor,
            @Value("${mizan.acquirer.max-concurrent-calls:8}") int maxConcurrentCalls,
            @Value("${mizan.acquirer.wait-for-a-turn:100ms}") Duration waitForATurn) {

        this.http = builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();

        // A 4xx is the acquirer answering. It disagreeing with a request is not it being down.
        this.breaker = new CircuitBreaker(
                "the acquirer",
                failuresBeforeGivingUp,
                leaveAloneFor,
                failed -> failed instanceof HttpClientErrorException);
        this.bulkhead = new Bulkhead("the acquirer", maxConcurrentCalls, waitForATurn);

        Gauge.builder("mizan.acquirer.breaker.open", breaker,
                        b -> b.state() == CircuitBreaker.State.OPEN ? 1 : 0)
                .description("1 while the acquirer is being left alone after failing repeatedly")
                .register(meters);
        Gauge.builder("mizan.acquirer.calls.in.flight", bulkhead, Bulkhead::inFlight)
                .description("Calls waiting on the acquirer right now")
                .register(meters);
        this.notSentBecauseOpen = notSent(meters, "breaker_open");
        this.notSentBecauseFull = notSent(meters, "too_many_waiting");
    }

    private static Counter notSent(MeterRegistry meters, String because) {
        return Counter.builder("mizan.acquirer.calls.not.sent")
                .description("Calls to the acquirer refused before being sent, by why")
                .tag("because", because)
                .register(meters);
    }

    /**
     * Sends the call if both guards allow it.
     *
     * <p>A refusal becomes {@code UPSTREAM_UNAVAILABLE} and never {@code UPSTREAM_TIMEOUT}: the
     * second means "sent, and whether it happened is unknown", which starts a resolution, and
     * nothing was sent here to resolve.
     */
    private <T> T guarded(Supplier<T> call) {
        try {
            return bulkhead.call(() -> breaker.call(call));

        } catch (CircuitBreaker.CircuitOpenException leftAlone) {
            // Debug, as risk's is. The breaker being open is the design working, and a warning per
            // payment during an outage buries the one line that says it started.
            notSentBecauseOpen.increment();
            log.debug("{}", leftAlone.getMessage());
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer has been failing, so it was not asked. Nothing was sent, and it "
                            + "is safe to try again shortly.",
                    leftAlone);

        } catch (Bulkhead.FullException full) {
            notSentBecauseFull.increment();
            log.debug("{}", full.getMessage());
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer is slow to answer and enough requests are already waiting on "
                            + "it. Nothing was sent, and it is safe to try again shortly.",
                    full);
        }
    }

    /** Asks the acquirer to reserve the money, using the payment's id as the request's. */
    public AcquirerDecision authorize(UUID paymentId, long amount, String currency, String card) {
        try {
            AcquirerResponse answer = guarded(() -> http.post()
                    .uri("/acquirer/authorizations")
                    .body(new AcquirerRequest(paymentId.toString(), amount, currency, card))
                    .retrieve()
                    .body(AcquirerResponse.class));

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
            guarded(() -> http.post()
                    .uri("/acquirer/authorizations/{reference}/" + what, acquirerReference)
                    .retrieve()
                    .toBodilessEntity());

        } catch (ResourceAccessException noAnswer) {
            log.warn("no answer from the acquirer asking it to {} {}", what, acquirerReference);
            throw new MizanException(
                    ErrorCode.UPSTREAM_TIMEOUT,
                    "The acquirer did not answer in time. Whether the payment was "
                            + what.replace("void", "voided").replace("capture", "captured")
                            + " is not yet known.",
                    noAnswer);
        } catch (HttpClientErrorException refused) {
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
     * Asks the acquirer to give money back.
     *
     * <p>Keyed on the refund's own reference, so a call whose answer was lost can be repeated
     * and gives the money back once. The acquirer does its own arithmetic on what is left,
     * which means this platform's limit and the acquirer's have to agree — and when they do
     * not, the acquirer wins, because it is the one holding the money.
     */
    public AcquirerRefund refund(String acquirerReference, String reference, long amount) {
        try {
            AcquirerRefundResponse answer = guarded(() -> http.post()
                    .uri("/acquirer/authorizations/{reference}/refund", acquirerReference)
                    .body(new AcquirerRefundRequest(reference, amount))
                    .retrieve()
                    .body(AcquirerRefundResponse.class));

            if (answer == null) {
                throw new MizanException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "The acquirer said nothing.");
            }
            return new AcquirerRefund(
                    answer.acquirerReference(), answer.amount(), answer.remaining());

        } catch (ResourceAccessException noAnswer) {
            // Whether the money went back is not known. Deciding it did not would let the
            // merchant refund it a second time, which is the expensive direction to be wrong
            // in. MIZ-52 is what resolves this by asking.
            log.warn("no answer from the acquirer refunding {}", acquirerReference, noAnswer);
            throw new MizanException(
                    ErrorCode.UPSTREAM_TIMEOUT,
                    "The acquirer did not answer in time. Whether the money has gone back is "
                            + "not yet known.",
                    noAnswer);

        } catch (HttpClientErrorException refused) {
            log.warn(
                    "the acquirer refused to refund {}: {}",
                    acquirerReference,
                    refused.getResponseBodyAsString());
            throw new MizanException(
                    ErrorCode.UNPROCESSABLE,
                    "The acquirer will not refund this payment: " + detailOf(refused),
                    refused);

        } catch (MizanException already) {
            throw already;
        } catch (Exception unreachable) {
            log.error("could not reach the acquirer to refund {}", acquirerReference, unreachable);
            throw new MizanException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The acquirer could not be reached.",
                    unreachable);
        }
    }

    /** The acquirer's own sentence, if it sent one, rather than this service's guess at it. */
    private static String detailOf(HttpClientErrorException refused) {
        org.springframework.http.ProblemDetail problem =
                refused.getResponseBodyAs(org.springframework.http.ProblemDetail.class);
        return problem == null || problem.getDetail() == null
                ? "it answered " + refused.getStatusCode().value()
                : problem.getDetail();
    }

    /** What the acquirer gave back. */
    public record AcquirerRefund(String acquirerReference, long amount, long remaining) {
    }

    private record AcquirerRefundRequest(String reference, long amount) {
    }

    private record AcquirerRefundResponse(
            String acquirerReference,
            String reference,
            String authorizationReference,
            long amount,
            String currency,
            long refundedInTotal,
            long remaining,
            Instant refundedAt) {
    }

    /**
     * Asks the acquirer what it did with a request, if anything.
     *
     * <p>Keyed on the payment's id, because that is what the request carried and what a
     * caller who never heard the answer still has. An empty answer is a real answer: this
     * acquirer has no record, so nothing was authorized.
     *
     * <p>Behind the same guards. A sweep asking about stuck payments during an outage is refused
     * like anything else, and the payments stay unresolved for the next sweep, which is exactly
     * what not being able to ask already meant.
     */
    public java.util.Optional<AcquirerDecision> lookUp(UUID paymentId) {
        try {
            return guarded(() -> http.get()
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
                    }));

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

    /**
     * What the acquirer decided, in this service's own words.
     *
     * @param state where the authorization is now at the acquirer, in the acquirer's words:
     *     {@code HELD} (approved, money reserved, not taken), {@code CAPTURED}, {@code VOIDED} or
     *     {@code REFUSED}. What a capture sweep needs, because a decision says only what was
     *     decided and not whether the money was later taken.
     */
    public record AcquirerDecision(
            String acquirerReference,
            boolean approved,
            String reason,
            String cardLastFour,
            Instant decidedAt,
            String state) {

        /** The acquirer's word for money taken. */
        static final String CAPTURED = "CAPTURED";

        /**
         * The acquirer's word for an approval whose money is reserved and not yet taken. Not this
         * platform's AUTHORIZED: the first version of the capture sweep assumed it was, and every
         * capture that never reached the acquirer went to a person instead of being cleared.
         * AcquirerStatesTest holds the two services' words together.
         */
        static final String HELD = "HELD";

        public boolean captured() {
            return CAPTURED.equals(state);
        }

        public boolean stillOnlyAuthorized() {
            return HELD.equals(state);
        }
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
                    decidedAt,
                    state);
        }
    }
}
