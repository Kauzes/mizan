package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AttemptResult
import dev.kauzes.mizan.merchant.domain.AttemptStep
import dev.kauzes.mizan.merchant.domain.PaymentAttempt
import dev.kauzes.mizan.merchant.domain.ProceedOutcome
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Where payment attempts are kept between launches. */
interface AttemptStore {
    suspend fun save(attempt: PaymentAttempt)
    suspend fun find(id: String): PaymentAttempt?
    suspend fun unfinished(): List<PaymentAttempt>
    fun recent(): Flow<List<PaymentAttempt>>
}

/**
 * Takes a payment on this phone, so that however the app is interrupted, the customer is charged once.
 *
 * **Every key is chosen before anything is sent.** Starting a payment writes it down, with a reference
 * and one idempotency key each for creating, authorizing and capturing it. After that, every request is
 * sent with its step's key, every answer is written down before the next step, and resuming after a
 * crash sends the same requests with the same keys. The platform answers a repeated request with what
 * it already did (IdempotencyInterceptor), so a request whose answer was lost is not a second charge.
 *
 * **Not knowing is not guessing.** An authorization or capture that timed out at the acquirer (504) may
 * have happened. The step moves to confirming, and the platform is asked what the payment is now rather
 * than the request being sent again or the payment being called failed.
 *
 * **The card is never kept.** It is needed only to authorize, and holding a card number on the phone is
 * not something a merchant's till should do. So a payment interrupted before its authorization was
 * answered asks for the card again. The same card makes the same request, which the platform replays; a
 * different card is a different request under the same key, which the platform refuses, and so does this.
 */
class PaymentTaker(
    private val api: PaymentsApi,
    private val store: AttemptStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Writes a new payment down, with its reference and all three keys. Sends nothing. */
    suspend fun start(amount: Long, currency: String, description: String?): PaymentAttempt {
        val id = newId()
        val at = now()
        val attempt = PaymentAttempt(
            id = id,
            reference = "phone-$id",
            amount = amount,
            currency = currency,
            description = description?.takeIf { it.isNotBlank() },
            createKey = newId(),
            authorizeKey = newId(),
            captureKey = newId(),
            createdAt = at,
            updatedAt = at,
        )
        store.save(attempt)
        return attempt
    }

    suspend fun unfinished(): List<PaymentAttempt> = store.unfinished()

    fun recent(): Flow<List<PaymentAttempt>> = store.recent()

    /**
     * Moves a payment on as far as it can go now, from whatever step it last reached.
     *
     * @param card the card, when the merchant has just entered one. Used for one authorization at most.
     */
    suspend fun proceed(attemptId: String, card: String? = null): ProceedOutcome {
        var attempt = requireNotNull(store.find(attemptId)) { "no payment attempt $attemptId" }
        var cardInHand = card

        // Each pass either finishes, waits, or moves to another step. Bounded, so two steps that keep
        // handing the payment to each other (a capture the books refuse, read back as still authorized)
        // stop and say so rather than spin.
        repeat(MAX_STEPS) {
            when (attempt.step) {
                AttemptStep.FINISHED -> return ProceedOutcome.Finished(attempt)

                AttemptStep.CREATING -> when (val created = api.create(
                    attempt.createKey, attempt.amount, attempt.currency, attempt.reference, attempt.description,
                )) {
                    is ApiResult.Ok -> attempt = save(attempt.copy(paymentId = created.value.id, step = AttemptStep.AUTHORIZING))
                    is ApiResult.Refused -> return waitingOrRefused(attempt, created)
                    is ApiResult.Unavailable -> return ProceedOutcome.Waiting(attempt, unreachable(created))
                    ApiResult.SignedOut -> return ProceedOutcome.SignedOut(attempt)
                }

                AttemptStep.AUTHORIZING -> {
                    val cardNow = cardInHand ?: return ProceedOutcome.NeedsCard(attempt)
                    cardInHand = null
                    when (val authorized = api.authorize(attempt.paymentId!!, attempt.authorizeKey, cardNow)) {
                        is ApiResult.Ok -> attempt = afterAuthorization(attempt, authorized.value)
                        is ApiResult.Refused -> when {
                            authorized.code == "IDEMPOTENCY_KEY_REUSED" -> return ProceedOutcome.CardDiffers(attempt)
                            // The payment moved on without this request being the one that moved it: a
                            // lost answer to an earlier try. Read what it is now.
                            authorized.status == 422 -> attempt = save(attempt.copy(step = AttemptStep.CONFIRMING_AUTHORIZATION))
                            else -> return waitingOrRefused(attempt, authorized)
                        }
                        is ApiResult.Unavailable ->
                            if (authorized.code == "UPSTREAM_TIMEOUT") {
                                attempt = save(attempt.copy(step = AttemptStep.CONFIRMING_AUTHORIZATION))
                                return ProceedOutcome.Waiting(attempt, "The bank did not answer in time. Whether the card was authorized is being worked out.")
                            } else {
                                return ProceedOutcome.Waiting(attempt, unreachable(authorized))
                            }
                        ApiResult.SignedOut -> return ProceedOutcome.SignedOut(attempt)
                    }
                }

                AttemptStep.CONFIRMING_AUTHORIZATION -> when (val found = api.find(attempt.paymentId!!)) {
                    is ApiResult.Ok -> when (found.value.status) {
                        // Nothing reached the acquirer. The authorization is sent again, with the card.
                        "CREATED" -> attempt = save(attempt.copy(step = AttemptStep.AUTHORIZING))
                        "AUTHORIZATION_UNKNOWN" -> return ProceedOutcome.Waiting(attempt, "The bank has not said yet whether the card was authorized.")
                        else -> attempt = afterAuthorization(attempt, found.value)
                    }
                    is ApiResult.Refused -> return waitingOrRefused(attempt, found)
                    is ApiResult.Unavailable -> return ProceedOutcome.Waiting(attempt, unreachable(found))
                    ApiResult.SignedOut -> return ProceedOutcome.SignedOut(attempt)
                }

                AttemptStep.CAPTURING -> when (val captured = api.capture(attempt.paymentId!!, attempt.captureKey)) {
                    is ApiResult.Ok -> attempt = afterCapture(attempt, captured.value)
                    is ApiResult.Refused ->
                        if (captured.status == 422) {
                            attempt = save(attempt.copy(step = AttemptStep.CONFIRMING_CAPTURE, detail = captured.detail))
                        } else {
                            return waitingOrRefused(attempt, captured)
                        }
                    is ApiResult.Unavailable ->
                        if (captured.code == "UPSTREAM_TIMEOUT") {
                            attempt = save(attempt.copy(step = AttemptStep.CONFIRMING_CAPTURE))
                            return ProceedOutcome.Waiting(attempt, "The bank did not answer in time. Whether the money was taken is being worked out.")
                        } else {
                            return ProceedOutcome.Waiting(attempt, unreachable(captured))
                        }
                    ApiResult.SignedOut -> return ProceedOutcome.SignedOut(attempt)
                }

                AttemptStep.CONFIRMING_CAPTURE -> when (val found = api.find(attempt.paymentId!!)) {
                    is ApiResult.Ok -> when (found.value.status) {
                        // Not taken. The capture is sent again with the same key, which the platform gave
                        // back when the earlier try failed.
                        "AUTHORIZED" -> attempt = save(attempt.copy(step = AttemptStep.CAPTURING))
                        else -> attempt = afterCapture(attempt, found.value)
                    }
                    is ApiResult.Refused -> return waitingOrRefused(attempt, found)
                    is ApiResult.Unavailable -> return ProceedOutcome.Waiting(attempt, unreachable(found))
                    ApiResult.SignedOut -> return ProceedOutcome.SignedOut(attempt)
                }
            }
        }
        return ProceedOutcome.Waiting(
            attempt,
            attempt.detail ?: "The payment did not settle into a result. Try again in a moment.",
        )
    }

    private suspend fun afterAuthorization(attempt: PaymentAttempt, payment: RemotePayment): PaymentAttempt {
        val withCard = attempt.copy(cardLastFour = payment.cardLastFour ?: attempt.cardLastFour)
        return save(
            when (payment.status) {
                "AUTHORIZED" -> withCard.copy(step = AttemptStep.CAPTURING, detail = null)
                "CAPTURED" -> withCard.finish(AttemptResult.CAPTURED, null)
                "HELD_FOR_REVIEW" -> withCard.finish(AttemptResult.HELD_FOR_REVIEW, "Held for review. Nobody has been charged.")
                "DECLINED" -> withCard.finish(AttemptResult.DECLINED, payment.declineReason ?: "The card was declined.")
                else -> withCard.finish(AttemptResult.REFUSED, "The payment is ${payment.status}.")
            },
        )
    }

    private suspend fun afterCapture(attempt: PaymentAttempt, payment: RemotePayment): PaymentAttempt = save(
        when (payment.status) {
            "CAPTURED" -> attempt.finish(AttemptResult.CAPTURED, null)
            else -> attempt.finish(AttemptResult.REFUSED, "The payment is ${payment.status}, not captured.")
        },
    )

    /** A refusal that may pass (busy, too many requests) waits; any other ends the payment. */
    private suspend fun waitingOrRefused(attempt: PaymentAttempt, refused: ApiResult.Refused): ProceedOutcome = when {
        refused.status == 429 -> ProceedOutcome.Waiting(attempt, "Too many payments at once. Try again in a moment.")
        refused.code == "CONTENDED" -> ProceedOutcome.Waiting(attempt, "The platform is still working on this payment. Try again in a moment.")
        else -> ProceedOutcome.Finished(save(attempt.finish(AttemptResult.REFUSED, refused.detail ?: "The platform refused it (${refused.status}).")))
    }

    private fun unreachable(unavailable: ApiResult.Unavailable) =
        "The platform could not be reached: ${unavailable.because}. Trying again will not charge twice."

    private fun PaymentAttempt.finish(result: AttemptResult, detail: String?) =
        copy(step = AttemptStep.FINISHED, result = result, detail = detail)

    private suspend fun save(attempt: PaymentAttempt): PaymentAttempt {
        val stamped = attempt.copy(updatedAt = now())
        store.save(stamped)
        return stamped
    }

    private companion object {
        const val MAX_STEPS = 8
    }
}
