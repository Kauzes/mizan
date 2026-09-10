import { useEffect, useRef, useState } from "react";
import { PlatformError, problemFrom } from "../api/problems";
import { formatMoney, parseMoney } from "../money/money";
import { useSession } from "../session/SessionProvider";
import type { Payment, Refund } from "./types";

/**
 * Giving money back, from the page somebody is already looking at.
 *
 * The first action in this console that moves money, which makes it the first one that has to
 * be hard to do by accident. Three things do that work, and none of them is the dialog being
 * called "confirm".
 *
 * The amount that can still be given back is on the screen before anything is typed, so the
 * limit is a fact rather than a refusal. The confirmation says the amount, the currency and
 * what will be left, in words: a dialog that asks "are you sure" and nothing else is a dialog
 * people learn to click through. And the idempotency key is chosen when the confirmation
 * opens rather than when the request is sent, so a double click and a retry after a lost
 * answer are the same request rather than two refunds.
 */
export function Refunding({
  payment,
  refunds,
  onRefunded,
}: {
  payment: Payment;
  refunds: readonly Refund[];
  onRefunded: () => void;
}) {
  const { session, caller, can } = useSession();
  const [asking, setAsking] = useState(false);
  const [typed, setTyped] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  /**
   * One key per intent, and one reference with it.
   *
   * Both are chosen when the confirmation opens. A key chosen at send time makes a double
   * click two refunds; a key that never changes makes the second, deliberate refund a replay
   * of the first.
   */
  const intent = useRef<{ key: string; reference: string } | null>(null);

  useEffect(() => {
    if (confirming && !intent.current) {
      const id = crypto.randomUUID();
      intent.current = {
        key: id,
        reference: `${payment.reference}-refund-${id.slice(0, 8)}`,
      };
    }
  }, [confirming, payment.reference]);

  if (!can("PAYMENT_WRITE") || payment.refundableAmount <= 0) {
    return null;
  }

  const money = (amount: number) => formatMoney({ amount, currency: payment.currency });
  const wanted = typed ? parseMoney(typed, payment.currency) : null;

  const tooMuch = wanted !== null && wanted > payment.refundableAmount;
  const nothing = wanted !== null && wanted <= 0;
  const unreadable = typed.trim() !== "" && wanted === null;
  const usable = wanted !== null && !tooMuch && !nothing;

  async function give() {
    if (!usable || !intent.current || !caller) {
      return;
    }
    setBusy(true);
    setRefusal(null);

    try {
      const response = await session.call(
        `/api/v1/merchants/${caller.merchantId}/payments/${payment.id}/refunds`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            // The whole reason the key is held in a ref: this is the second time this
            // request might be sent, and it has to be the same request.
            "Idempotency-Key": intent.current.key,
          },
          body: JSON.stringify({
            amount: wanted,
            currency: payment.currency,
            reference: intent.current.reference,
            reason: "Refunded from the console",
          }),
        },
      );

      if (!response.ok) {
        throw new PlatformError(await problemFrom(response));
      }

      intent.current = null;
      setConfirming(false);
      setAsking(false);
      setTyped("");
      onRefunded();
    } catch (failed) {
      setRefusal(
        failed instanceof PlatformError
          ? sentence(failed)
          : "This platform could not be reached. Nothing was given back.",
      );
    } finally {
      setBusy(false);
    }
  }

  if (!asking) {
    return (
      <p>
        <button type="button" onClick={() => setAsking(true)}>
          Refund
        </button>{" "}
        <span className="muted">{money(payment.refundableAmount)} can still be given back</span>
      </p>
    );
  }

  return (
    <div className="refunding">
      <h2>Give money back</h2>

      <p className="muted">
        {money(payment.amount)} was taken
        {payment.refundedAmount > 0 ? ` and ${money(payment.refundedAmount)} given back` : ""}.{" "}
        <strong>{money(payment.refundableAmount)}</strong> can still be refunded
        {refunds.length > 0 ? `, across ${refunds.length + 1} refunds` : ""}.
      </p>

      {!confirming ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (usable) {
              setConfirming(true);
            }
          }}
        >
          <label htmlFor="refund-amount">How much, in {payment.currency}</label>
          <input
            id="refund-amount"
            inputMode="decimal"
            autoFocus
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
          />
          <button
            type="button"
            onClick={() => setTyped(majorUnits(payment.refundableAmount, payment.currency))}
          >
            All of it
          </button>

          {tooMuch ? (
            <p className="refusal" role="alert">
              Only {money(payment.refundableAmount)} is left to give back.
            </p>
          ) : null}
          {nothing ? (
            <p className="refusal" role="alert">
              A refund has to be for more than nothing.
            </p>
          ) : null}
          {unreadable ? (
            <p className="refusal" role="alert">
              That is not an amount in {payment.currency}.
            </p>
          ) : null}

          <p>
            <button type="submit" disabled={!usable}>
              Continue
            </button>{" "}
            <button type="button" onClick={() => setAsking(false)}>
              Cancel
            </button>
          </p>
        </form>
      ) : (
        <div role="group" aria-label="Confirm this refund">
          <p>
            Give <strong>{money(wanted ?? 0)}</strong> back to this customer. That leaves{" "}
            <strong>{money(payment.refundableAmount - (wanted ?? 0))}</strong> of{" "}
            {money(payment.amount)} refundable.
          </p>

          {refusal ? (
            <p className="refusal" role="alert">
              {refusal}
            </p>
          ) : null}

          <p>
            <button type="button" onClick={() => void give()} disabled={busy}>
              {busy ? "Giving it back…" : `Refund ${money(wanted ?? 0)}`}
            </button>{" "}
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                // A new intent next time. Somebody who went back to change the amount means
                // a different refund, and reusing the key would answer with the old one.
                intent.current = null;
                setConfirming(false);
              }}
            >
              Back
            </button>
          </p>
        </div>
      )}
    </div>
  );
}

/**
 * A refusal in the merchant's terms, chosen by the code rather than the status.
 *
 * The codes are the contract; the status is a summary of it. Branching here is the console
 * keeping the same promise the API documentation asks every client to keep.
 */
function sentence(failed: PlatformError): string {
  switch (failed.problem.code) {
    case "UNPROCESSABLE":
      return failed.problem.detail;
    case "CONFLICT":
      return "That refund has already been made.";
    case "IDEMPOTENCY_KEY_REUSED":
      return "This refund was sent with different details a moment ago. Start again.";
    case "UPSTREAM_UNAVAILABLE":
      return "The acquirer could not be reached, so nothing was given back. Try again shortly.";
    case "UPSTREAM_TIMEOUT":
      return (
        "The acquirer did not answer in time. Whether the money went back is not yet known — "
        + "check this payment again before refunding it a second time."
      );
    case "FORBIDDEN":
      return "This account may not refund payments.";
    default:
      return failed.problem.detail;
  }
}

/** The refundable amount as a person would type it, for the "all of it" button. */
function majorUnits(amount: number, currency: string): string {
  const digits = new Intl.NumberFormat("en", { style: "currency", currency })
    .resolvedOptions()
    .maximumFractionDigits ?? 2;

  return (amount / 10 ** digits).toFixed(digits);
}
