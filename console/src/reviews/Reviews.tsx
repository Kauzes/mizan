import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { PlatformError } from "../api/problems";
import { formatMoney } from "../money/money";
import { readable } from "../payments/filters";
import type { Payment } from "../payments/types";
import { useSession } from "../session/SessionProvider";

/**
 * The queue an analyst works.
 *
 * MIZ-59 built the two verbs and the bounded loop behind them. This is the screen somebody
 * uses them from, and almost everything on it is about making a decision easy to make well:
 * why the payment was held is on the row rather than a click away, a reason is required
 * before either button does anything, and what colleagues already decided is on the same page
 * as the drift it caused — because "what have we decided" and "what has the platform learned
 * from that" are one question asked twice.
 */
interface Ruled {
  readonly rulings: ReadonlyArray<Record<string, unknown>>;
  readonly learnedAdjustment: number | null;
  readonly known: boolean;
}

export function Reviews() {
  const { session, caller, can } = useSession();
  const merchantId = caller?.merchantId;

  const [waiting, setWaiting] = useState<readonly Payment[] | null>(null);
  const [ruled, setRuled] = useState<Ruled | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [reread, setReread] = useState(0);

  useEffect(() => {
    if (!merchantId || !can("REVIEW_RULE")) {
      return;
    }
    let current = true;
    setRefusal(null);

    const mine = `/api/v1/merchants/${merchantId}/reviews`;
    Promise.all([
      session.json<Payment[]>(mine),
      session.json<Ruled>(`${mine}/rulings?limit=20`),
    ])
      .then(([queue, decided]) => {
        if (current) {
          setWaiting(queue);
          setRuled(decided);
        }
      })
      .catch((failed: unknown) => {
        if (current) {
          setRefusal(
            failed instanceof PlatformError
              ? failed.problem.detail
              : "This platform could not be reached.",
          );
        }
      });

    return () => {
      current = false;
    };
  }, [session, merchantId, can, reread]);

  const again = useCallback(() => setReread((count) => count + 1), []);

  if (!can("REVIEW_RULE")) {
    return (
      <main>
        <h1>Review queue</h1>
        <p className="muted">This account does not rule on held payments.</p>
      </main>
    );
  }

  return (
    <main>
      <h1>Review queue</h1>

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}

      {waiting === null ? <p className="muted">Looking…</p> : null}

      {waiting?.length === 0 ? (
        <p className="muted">Nothing is waiting. Every held payment has been ruled on.</p>
      ) : null}

      {waiting?.map((payment) => (
        <Held key={payment.id} payment={payment} onRuled={again} />
      ))}

      <Learned ruled={ruled} />
    </main>
  );
}

/** One held payment, with why it was held and the two things to do about it. */
function Held({ payment, onRuled }: { payment: Payment; onRuled: () => void }) {
  const { session, caller } = useSession();
  const [why, setWhy] = useState("");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  async function rule(verb: "release" | "refuse") {
    if (!caller || !why.trim()) {
      return;
    }
    setBusy(true);
    setRefusal(null);

    try {
      const response = await session.call(
        `/api/v1/merchants/${caller.merchantId}/reviews/${payment.id}/${verb}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ why: why.trim() }),
        },
      );

      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        // A colleague who ruled while this analyst was reading is not an error, and saying
        // "422" at somebody is not telling them what happened.
        throw new Error(
          (problem as { detail?: string } | null)?.detail ??
            "This platform could not be reached.",
        );
      }
      onRuled();
    } catch (failed) {
      setRefusal(failed instanceof Error ? failed.message : String(failed));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="held">
      <p className="held-what">
        <strong>{formatMoney({ amount: payment.amount, currency: payment.currency })}</strong>{" "}
        <Link to={`/payments/${payment.id}`}>
          <code>{payment.reference}</code>
        </Link>{" "}
        <span className="muted">
          {payment.cardLastFour ? `•••• ${payment.cardLastFour} · ` : ""}
          {new Date(payment.createdAt).toLocaleString()}
        </span>
      </p>

      {/* Why it was held, on the row. A decision that needs three clicks of context first is
          a decision made without it. */}
      <p className="muted held-why">
        {readable(payment.riskVerdict ?? "unscored")}
        {payment.riskScore === null ? "" : ` · ${payment.riskScore}`}
        {payment.riskReasons ? ` — ${payment.riskReasons}` : ""}
      </p>

      <div className="held-rule">
        <label htmlFor={`why-${payment.id}`}>Why</label>
        <input
          id={`why-${payment.id}`}
          value={why}
          placeholder="known customer, they called to confirm"
          onChange={(event) => setWhy(event.target.value)}
        />
        {/* Required here as well as at the API. The console should not be where somebody
            learns a reason is needed by being refused. */}
        <button type="button" disabled={busy || !why.trim()} onClick={() => void rule("release")}>
          Release
        </button>
        <button type="button" disabled={busy || !why.trim()} onClick={() => void rule("refuse")}>
          Refuse
        </button>
      </div>

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}
    </section>
  );
}

/** What colleagues decided, and what the platform learned from it. */
function Learned({ ruled }: { ruled: Ruled | null }) {
  if (!ruled) {
    return null;
  }

  return (
    <>
      <h2>What has been decided</h2>

      <p className="muted">{drift(ruled)}</p>

      {ruled.rulings.length === 0 ? (
        <p className="muted">Nothing has been ruled on yet.</p>
      ) : (
        <ul className="plain">
          {ruled.rulings.map((one, index) => (
            <li key={String(one["payment_id"] ?? index)}>
              <strong>{readable(String(one["ruling"] ?? ""))}</strong>{" "}
              <span className="muted">
                by {String(one["ruled_by"] ?? "somebody")} — {String(one["why"] ?? "")}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/**
 * The drift, in words rather than as a number nobody can interpret.
 *
 * "+5" says nothing to a person working a queue. What they need to know is which direction
 * the platform has moved and that it is bounded, because that is what tells them whether to
 * keep ruling or to ask somebody to look at the thresholds.
 */
function drift(ruled: Ruled): string {
  if (!ruled.known) {
    return "How far this platform has moved its line could not be read just now.";
  }
  const moved = ruled.learnedAdjustment ?? 0;
  if (moved === 0) {
    return "This platform has not moved its line for you. It takes three rulings the same "
      + "way before anything moves at all.";
  }
  if (moved > 0) {
    return `Consistent releases have raised this merchant's line by ${moved} points, so fewer `
      + "payments are held. It moves at most twenty, and a person can set it back.";
  }
  return `Consistent refusals have lowered this merchant's line by ${-moved} points, so more `
    + "payments are held. It moves at most twenty, and a person can set it back.";
}
