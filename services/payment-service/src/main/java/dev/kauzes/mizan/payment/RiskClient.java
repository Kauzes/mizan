package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.identity.ServiceCredential;
import dev.kauzes.mizan.common.web.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asking risk what it thinks, and carrying on when it cannot say.
 *
 * <p>Two things make this different from every other outbound call on this platform.
 *
 * <p>The timeout is <em>shorter than the acquirer's</em>. A guard that takes as long as the
 * thing it guards has stopped being a guard and started being a second acquirer, and the
 * customer waits for both.
 *
 * <p>And there is a breaker. Everywhere else, a call that fails is retried or resolved, because
 * the answer matters enough to wait for. Here it does not: risk being down must cost a few
 * timeouts in total rather than one per payment, or the guard becomes the outage.
 */
@Component
public class RiskClient {

    private static final Logger log = LoggerFactory.getLogger(RiskClient.class);

    /** What risk decided, or that it could not be asked. */
    public record Assessment(
            String verdict, Integer score, List<String> reasons, Instant at) {

        /**
         * What is recorded when risk could not be asked at all.
         *
         * <p>A verdict of its own rather than a null or a pretend approval. A merchant asking
         * why a payment went through unchecked, and an analyst reviewing a day of them
         * afterwards, both need this to be a fact rather than an absence.
         */
        static Assessment unavailable(String because) {
            return new Assessment("UNAVAILABLE", null, List.of(because), Instant.now());
        }

        public boolean isBlock() {
            return "BLOCK".equals(verdict);
        }

        public boolean isReview() {
            return "REVIEW".equals(verdict);
        }
    }

    private final RestClient http;
    private final CircuitBreaker breaker;

    public RiskClient(
            RestClient.Builder builder,
            @Value("${mizan.risk.base-url:http://localhost:8084}") String baseUrl,
            @Value("${mizan.risk.timeout:500ms}") Duration timeout,
            @Value("${mizan.risk.failures-before-giving-up:5}") int failuresBeforeOpening,
            @Value("${mizan.risk.leave-alone-for:30s}") Duration leaveAloneFor,
            @Value("${mizan.internal.service-token:}") String serviceToken) {

        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(ServiceCredential.HEADER, serviceToken)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)))
                .build();
        this.breaker = new CircuitBreaker("risk", failuresBeforeOpening, leaveAloneFor);
    }

    /**
     * What risk thinks of this payment.
     *
     * <p>Never throws. Whatever goes wrong — a timeout, a refusal, the breaker being open — is
     * an {@code UNAVAILABLE} assessment rather than an exception, because the decision about
     * what to do when risk cannot be asked belongs to the payment flow and is made once, in one
     * place, rather than being implied by where an exception happens to be caught.
     */
    public Assessment assess(Payment payment, String card) {
        try {
            return breaker.call(() -> {
                Scored answer = http.post()
                        .uri("/api/v1/risk/scores")
                        .body(new ScoreRequest(
                                payment.id(),
                                payment.merchantId(),
                                payment.money().amount(),
                                payment.money().currency().getCurrencyCode(),
                                // A fingerprint, not the card. The same four digits this
                                // service keeps and publishes, which is all risk works with.
                                "last4:" + card.substring(card.length() - 4),
                                null,
                                Instant.now()))
                        .retrieve()
                        .body(Scored.class);

                if (answer == null) {
                    throw new IllegalStateException("risk said nothing");
                }
                return new Assessment(
                        answer.verdict(),
                        answer.score(),
                        answer.signals().stream().map(Signal::because).toList(),
                        answer.at());
            });

        } catch (CircuitBreaker.CircuitOpenException notAsked) {
            // Not a warning. The breaker being open is the design working, and logging it at
            // warn for every payment during an outage is how a log becomes unreadable at
            // exactly the moment somebody needs to read it.
            log.debug("risk was not asked about {}: {}", payment.id(), notAsked.getMessage());
            return Assessment.unavailable("risk was not answering, so it was not asked");

        } catch (RuntimeException couldNotAsk) {
            log.warn("could not ask risk about {}: {}", payment.id(), couldNotAsk.getMessage());
            return Assessment.unavailable(
                    "risk could not be asked: " + couldNotAsk.getClass().getSimpleName());
        }
    }

    /**
     * Tells risk what a person decided, and does not mind if it is not listening.
     *
     * <p>The same judgement as scoring: risk is a guard, not an invariant, and an analyst
     * should not be unable to release a customer's payment because a scorer is down. What is
     * lost is one ruling's worth of learning, which is smaller than a queue that cannot be
     * worked.
     */
    public void ruled(Payment payment, String ruling, String who, String why) {
        try {
            breaker.call(() -> http.post()
                    .uri("/api/v1/risk/rulings")
                    .body(new RulingRequest(
                            payment.merchantId(),
                            payment.id(),
                            ruling,
                            payment.riskScore(),
                            payment.riskReasons(),
                            who,
                            why))
                    .retrieve()
                    .toBodilessEntity());

        } catch (RuntimeException couldNotTell) {
            // At warn, because a ruling the scorer never hears about is the feedback loop
            // quietly not happening, and that is worth noticing even though it is survivable.
            log.warn(
                    "risk was not told that {} was {} by {}: {}",
                    payment.id(),
                    ruling,
                    who,
                    couldNotTell.getMessage());
        }
    }

    private record RulingRequest(
            UUID merchantId,
            UUID paymentId,
            String ruling,
            Integer riskScore,
            String riskReasons,
            String ruledBy,
            String why) {
    }

    /** What state the breaker is in, for anything that wants to show it. */
    public CircuitBreaker breaker() {
        return breaker;
    }

    private record ScoreRequest(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String cardFingerprint,
            String cardCountry,
            Instant at) {

        /** A card number is not something to print, even a fingerprint of one. */
        @Override
        public String toString() {
            return "ScoreRequest[paymentId=" + paymentId + ", amount=" + amount + "]";
        }
    }

    private record Scored(
            UUID paymentId,
            String verdict,
            int score,
            int reviewAbove,
            int blockAbove,
            List<Signal> signals,
            Instant at) {
    }

    private record Signal(String rule, int contribution, String because) {
    }
}
