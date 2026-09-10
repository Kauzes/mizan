import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { PlatformError } from "../api/problems";
import { formatMoney } from "../money/money";
import { useSession } from "../session/SessionProvider";
import { readable } from "./filters";
import type { Attempt, Delivery, Entry, Payment, Refund } from "./types";

/**
 * One payment, and everything that happened to it.
 *
 * Three services answer this page, and it asks them separately rather than through something
 * that composes them. A composing endpoint would be one fewer round trip and one more thing
 * that knows about payments, the ledger and webhooks at once — and the first place a change to
 * any of the three would break. The page is also the only caller that wants all three, so the
 * composition belongs where the question is asked.
 *
 * Each section is asked for only if the person may read it. A panel that renders a refusal is
 * a panel that told somebody a thing exists.
 */
export function PaymentDetail() {
  const { session, caller, can } = useSession();
  const { paymentId } = useParams();
  const merchantId = caller?.merchantId;

  const [payment, setPayment] = useState<Payment | null>(null);
  const [refunds, setRefunds] = useState<readonly Refund[]>([]);
  const [entries, setEntries] = useState<readonly Entry[]>([]);
  const [deliveries, setDeliveries] = useState<readonly Delivery[]>([]);
  const [refusal, setRefusal] = useState<string | null>(null);

  useEffect(() => {
    if (!merchantId || !paymentId) {
      return;
    }
    let current = true;
    const mine = `/api/v1/merchants/${merchantId}`;

    async function read() {
      const found = await session.json<Payment>(`${mine}/payments/${paymentId}`);
      if (!current) {
        return;
      }
      setPayment(found);

      const theirRefunds = await session.json<Refund[]>(
        `${mine}/payments/${paymentId}/refunds`,
      );
      if (!current) {
        return;
      }
      setRefunds(theirRefunds);

      if (can("ENTRY_READ")) {
        // What the payment and its refunds each wrote down. Asked for by id rather than
        // searched for, because the payment already knows which entries it produced.
        const ids = [found.ledgerEntryId, ...theirRefunds.map((refund) => refund.ledgerEntryId)]
          .filter((id): id is string => Boolean(id));
        const written = await Promise.all(
          ids.map((id) => session.json<Entry>(`${mine}/entries/${id}`)),
        );
        if (current) {
          setEntries(written);
        }
      }

      if (can("WEBHOOK_READ")) {
        const sent = await session.json<Delivery[]>(
          `${mine}/webhook-deliveries?paymentId=${paymentId}`,
        );
        if (current) {
          setDeliveries(sent);
        }
      }
    }

    setRefusal(null);
    read().catch((failed: unknown) => {
      if (!current) {
        return;
      }
      setRefusal(
        failed instanceof PlatformError
          ? failed.problem.detail
          : "This platform could not be reached.",
      );
    });

    return () => {
      current = false;
    };
  }, [session, merchantId, paymentId, can]);

  if (refusal) {
    return (
      <main>
        <Link to="/payments">Payments</Link>
        <p className="refusal" role="alert">
          {refusal}
        </p>
      </main>
    );
  }

  if (!payment) {
    return (
      <main aria-busy="true">
        <p className="muted">Looking…</p>
      </main>
    );
  }

  const money = (amount: number) => formatMoney({ amount, currency: payment.currency });

  return (
    <main>
      <nav className="crumbs">
        <Link to="/payments">Payments</Link>
      </nav>

      <h1>
        {money(payment.amount)} <span className="muted">{payment.reference}</span>
      </h1>
      <p>
        <span className={`status ${payment.status.toLowerCase()}`}>
          {readable(payment.status)}
        </span>
        {payment.declineReason ? (
          <span className="muted"> — {payment.declineReason}</span>
        ) : null}
      </p>

      <dl className="facts">
        <dt>Created</dt>
        <dd>{when(payment.createdAt)}</dd>
        <dt>Card</dt>
        <dd>{payment.cardLastFour ? `•••• ${payment.cardLastFour}` : "not presented yet"}</dd>
        <dt>Acquirer reference</dt>
        <dd>{payment.acquirerReference ?? "—"}</dd>
        <dt>Refunded</dt>
        <dd>
          {money(payment.refundedAmount)}
          {payment.refundableAmount > 0 ? (
            <span className="muted"> ({money(payment.refundableAmount)} still refundable)</span>
          ) : null}
        </dd>
      </dl>

      <WhatRiskThought payment={payment} />

      <h2>What happened</h2>
      <ol className="timeline">
        {payment.history.map((step, index) => (
          <li key={`${step.at}-${index}`}>
            <span className="at">{when(step.at)}</span>
            <span>
              {step.from ? `${readable(step.from)} → ` : ""}
              <strong>{readable(step.to)}</strong>
              {step.because ? <span className="muted"> — {step.because}</span> : null}
            </span>
          </li>
        ))}
      </ol>

      {refunds.length > 0 ? (
        <>
          <h2>Refunds</h2>
          <ul className="plain">
            {refunds.map((refund) => (
              <li key={refund.id}>
                {money(refund.amount)} · {readable(refund.status)}
                {refund.reason ? <span className="muted"> — {refund.reason}</span> : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {can("ENTRY_READ") ? <Books entries={entries} /> : null}
      {can("WEBHOOK_READ") ? <Told deliveries={deliveries} /> : null}
    </main>
  );
}

/**
 * What the platform thought, including when it could not think at all.
 *
 * UNAVAILABLE is a verdict rather than an absence: it says the scorer could not be asked, and
 * a merchant looking at a day of those is asking a different question from one looking at a
 * payment that was never scored.
 */
function WhatRiskThought({ payment }: { payment: Payment }) {
  return (
    <>
      <h2>What this platform thought</h2>
      {payment.riskVerdict === null ? (
        <p className="muted">This payment was never scored: it has not been authorized yet.</p>
      ) : payment.riskVerdict === "UNAVAILABLE" ? (
        <p>
          The scorer could not be asked, so this payment went through unscored.
          {payment.riskReasons ? <span className="muted"> {payment.riskReasons}</span> : null}
        </p>
      ) : (
        <p>
          <strong>{readable(payment.riskVerdict)}</strong>
          {payment.riskScore === null ? null : <span className="muted"> · {payment.riskScore}</span>}
          {payment.riskReasons ? <span className="muted"> — {payment.riskReasons}</span> : null}
        </p>
      )}

      {payment.reviewRuling ? (
        <p>
          {readable(payment.reviewRuling)} by <code>{payment.reviewRuledBy}</code>
          {payment.reviewRuledAt ? <span className="muted"> {when(payment.reviewRuledAt)}</span> : null}
        </p>
      ) : null}
    </>
  );
}

/** The entries this payment produced, each with its postings summing visibly to zero. */
function Books({ entries }: { entries: readonly Entry[] }) {
  return (
    <>
      <h2>In the books</h2>
      {entries.length === 0 ? (
        <p className="muted">
          Nothing yet. An authorization is a promise that the money is there rather than a
          movement of it, and the books record movements.
        </p>
      ) : (
        entries.map((entry) => (
          <div key={entry.id} className="entry">
            <p>
              <code>{entry.externalReference}</code>{" "}
              <span className="muted">{entry.description}</span>
            </p>
            <table className="postings">
              <tbody>
                {entry.postings.map((posting, index) => (
                  <tr key={`${entry.id}-${index}`}>
                    <td>{posting.accountCode}</td>
                    <td className="right">
                      {formatMoney({ amount: posting.amount, currency: posting.currency })}
                    </td>
                    <td className="muted">{posting.direction.toLowerCase()}</td>
                  </tr>
                ))}
                <tr className="sum">
                  <td>sums to</td>
                  <td className="right">
                    {formatMoney({
                      amount: entry.postings.reduce((total, one) => total + one.amount, 0),
                      currency: entry.postings[0]?.currency ?? "TRY",
                    })}
                  </td>
                  <td className="muted">every entry, always</td>
                </tr>
              </tbody>
            </table>
          </div>
        ))
      )}
    </>
  );
}

/** What was sent to the merchant's own endpoints about this payment, and how it went. */
function Told({ deliveries }: { deliveries: readonly Delivery[] }) {
  return (
    <>
      <h2>What your endpoints were told</h2>
      {deliveries.length === 0 ? (
        <p className="muted">Nothing was sent about this payment.</p>
      ) : (
        <ul className="plain">
          {deliveries.map((delivery) => (
            <li key={delivery.id}>
              <code>{delivery.event_type}</code> · {readable(delivery.status)}
              {delivery.last_status_code ? ` · answered ${delivery.last_status_code}` : ""}
              {delivery.attempts > 1 ? (
                <span className="muted"> after {delivery.attempts} attempts</span>
              ) : null}
              {delivery.last_error ? (
                <span className="muted"> — {delivery.last_error}</span>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

export type { Attempt };

function when(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  });
}
