package dev.kauzes.mizan.merchant.domain

/**
 * Where a payment taken on this phone has got to. MIZ-99.
 *
 * Written down before anything is sent, and after every answer, so that an app killed at any moment
 * resumes from the step it reached rather than starting again. Every request of a payment has its own
 * idempotency key, chosen once, here, when the payment is started: a retry after a crash, a lost answer
 * or a second tap is then the same request, and the platform answers it with what it already did.
 */
enum class AttemptStep {
    /** The payment is being recorded on the platform. */
    CREATING,

    /** The acquirer is being asked to reserve the money. Needs the card, which is never stored. */
    AUTHORIZING,

    /** An authorization was sent and its answer did not arrive. The platform is asked what happened. */
    CONFIRMING_AUTHORIZATION,

    /** The money is being taken. */
    CAPTURING,

    /** A capture was sent and its answer did not arrive. The platform is asked what happened. */
    CONFIRMING_CAPTURE,

    FINISHED,
}

/** How a finished payment ended. */
enum class AttemptResult {
    CAPTURED,

    /** Risk held it for a person. Nobody was charged; it is not a decline. */
    HELD_FOR_REVIEW,

    /** The acquirer refused the card. */
    DECLINED,

    /** The platform refused the request itself, for a reason in [PaymentAttempt.detail]. */
    REFUSED,
}

data class PaymentAttempt(
    val id: String,
    val reference: String,
    val amount: Long,
    val currency: String,
    val description: String?,
    val createKey: String,
    val authorizeKey: String,
    val captureKey: String,
    val paymentId: String? = null,
    val cardLastFour: String? = null,
    val step: AttemptStep = AttemptStep.CREATING,
    val result: AttemptResult? = null,
    val detail: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** What moving a payment on came to. */
sealed interface ProceedOutcome {
    val attempt: PaymentAttempt

    /** It reached an end: captured, held, declined or refused. */
    data class Finished(override val attempt: PaymentAttempt) : ProceedOutcome

    /** The next step is an authorization, and the card has to be entered for it. */
    data class NeedsCard(override val attempt: PaymentAttempt) : ProceedOutcome

    /** It cannot move on right now, and trying again later is safe: the same keys are used. */
    data class Waiting(override val attempt: PaymentAttempt, val because: String) : ProceedOutcome

    /**
     * The authorization's key was already used with a different card. Charging this card would be a new
     * payment, not a retry of this one, so the platform refuses and the app says so.
     */
    data class CardDiffers(override val attempt: PaymentAttempt) : ProceedOutcome

    data class SignedOut(override val attempt: PaymentAttempt) : ProceedOutcome
}
