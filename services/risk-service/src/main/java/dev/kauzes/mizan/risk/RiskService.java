package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.risk.RiskRequests.ScoreRequest;
import dev.kauzes.mizan.risk.RiskRequests.ScoreResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scoring a payment, with whatever is known about the merchant.
 *
 * <p>Thin on purpose. Everything that decides anything is in {@link Scorer}, which is a pure
 * function; this is the part that goes and finds what to give it. Keeping the two apart is what
 * lets the decisions be tested without a database and the lookups be changed without touching
 * the decisions — which matters, because MIZ-57 replaces every lookup here and should not have
 * to think about a single rule while doing it.
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final Scorer scorer;
    private final Thresholds thresholds;
    private final ObservedBehaviour observed;

    public RiskService(Scorer scorer, Thresholds thresholds, ObservedBehaviour observed) {
        this.scorer = scorer;
        this.thresholds = thresholds;
        this.observed = observed;
    }

    public ScoreResponse score(ScoreRequest request) {
        WhatWeKnow known = whatWeKnowAbout(request);
        Scorer.Score score = scorer.score(request, known);

        // At info, and with the reasons, because this is the record of why a payment was
        // stopped. A held payment whose reason nobody wrote down is one nobody can answer a
        // merchant about.
        if (score.verdict() != Verdict.APPROVE) {
            log.info(
                    "{} for payment {} of merchant {}, score {}: {}",
                    score.verdict(),
                    request.paymentId(),
                    request.merchantId(),
                    score.total(),
                    score.signals().stream().map(Signal::because).toList());
        }

        return new ScoreResponse(
                request.paymentId(),
                score.verdict(),
                score.total(),
                known.reviewAbove(),
                known.blockAbove(),
                score.signals(),
                request.at());
    }

    /**
     * What this service can say about the merchant and the card.
     *
     * <p>Built from what has actually happened, which is the whole point of MIZ-57: "unusual"
     * now means unusual for this merchant rather than unusual against a number somebody chose.
     * The rules did not change when this did, which was the reason for keeping them a pure
     * function of what they are given.
     */
    private WhatWeKnow whatWeKnowAbout(ScoreRequest request) {
        int[] lines = thresholds.forMerchant(request.merchantId());
        return observed.about(
                request.merchantId(),
                request.cardFingerprint(),
                request.at(),
                lines[0],
                lines[1]);
    }
}
