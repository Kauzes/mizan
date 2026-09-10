import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { fetchPage, type Page } from "../api/paged";
import { PlatformError } from "../api/problems";
import { formatMoney } from "../money/money";
import { useSession } from "../session/SessionProvider";
import {
  DEFAULT_SIZE,
  EVERY_STATUS,
  EVERY_VERDICT,
  describe,
  isEverything,
  readFilters,
  readable,
  toQuery,
  toSearchParams,
  type Filters,
} from "./filters";
import type { PaymentRow } from "./types";

export function Payments() {
  const { session, caller } = useSession();
  const [params, setParams] = useSearchParams();
  const filters = readFilters(params);

  const [found, setFound] = useState<Page<PaymentRow> | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const merchantId = caller?.merchantId;
  const query = toQuery(filters);

  useEffect(() => {
    if (!merchantId) {
      return;
    }
    let current = true;
    setLoading(true);
    setRefusal(null);

    fetchPage<PaymentRow>(session, `/api/v1/merchants/${merchantId}/payments?${query}`)
      .then((page) => {
        if (current) {
          setFound(page);
        }
      })
      .catch((failed: unknown) => {
        if (!current) {
          return;
        }
        setFound(null);
        setRefusal(
          failed instanceof PlatformError
            ? failed.problem.detail
            : "This platform could not be reached.",
        );
      })
      .finally(() => {
        if (current) {
          setLoading(false);
        }
      });

    // Answers can arrive out of order when somebody types quickly, and the slow one would
    // overwrite the one they are actually looking at.
    return () => {
      current = false;
    };
  }, [session, merchantId, query]);

  const change = useCallback(
    (next: Partial<Filters>) => {
      // Any change to what is being asked returns to the first page. Staying on page four of
      // a different search shows an empty table to somebody who has just narrowed to five
      // results, and reads as "nothing found".
      setParams(toSearchParams({ ...filters, ...next, page: next.page ?? 0 }));
    },
    [filters, setParams],
  );

  return (
    <main>
      <h1>Payments</h1>

      <Filtering filters={filters} onChange={change} />

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}

      {loading && !found ? <p className="muted">Looking…</p> : null}

      {found && found.items.length > 0 ? (
        <>
          <table className="payments">
            <caption className="sr-only">
              {found.total} payments, newest first, page {found.page + 1} of {found.pages}
            </caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Reference</th>
                <th scope="col" className="right">
                  Amount
                </th>
                <th scope="col">Status</th>
                <th scope="col">Risk</th>
                <th scope="col">Card</th>
              </tr>
            </thead>
            <tbody>
              {found.items.map((payment) => (
                <tr key={payment.id}>
                  <td>{when(payment.createdAt)}</td>
                  <td>
                    <Link to={`/payments/${payment.id}`}>
                      <code>{payment.reference}</code>
                    </Link>
                  </td>
                  <td className="right">
                    {formatMoney({ amount: payment.amount, currency: payment.currency })}
                  </td>
                  <td>
                    <span className={`status ${payment.status.toLowerCase()}`}>
                      {readable(payment.status)}
                    </span>
                  </td>
                  <td className="muted">
                    {payment.riskVerdict
                      ? `${readable(payment.riskVerdict)}${
                          payment.riskScore === null ? "" : ` (${payment.riskScore})`
                        }`
                      : "—"}
                  </td>
                  <td className="muted">
                    {payment.cardLastFour ? `•••• ${payment.cardLastFour}` : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <Paging page={found} onChange={change} />
        </>
      ) : null}

      {found && found.items.length === 0 ? <Nothing filters={filters} /> : null}
    </main>
  );
}

/**
 * Why the table is empty.
 *
 * A merchant who filtered themselves into nothing and one who has never taken a payment see
 * the same blank table otherwise, and only one of them has something they can fix.
 */
function Nothing({ filters }: { filters: Filters }) {
  if (isEverything(filters)) {
    return <p className="muted">No payments yet.</p>;
  }
  return (
    <p className="muted">
      No payments with {describe(filters).join(", and ")}.
    </p>
  );
}

function Paging({
  page,
  onChange,
}: {
  page: Page<PaymentRow>;
  onChange: (next: Partial<Filters>) => void;
}) {
  return (
    <nav className="paging" aria-label="Pages">
      <button
        type="button"
        disabled={page.page === 0}
        onClick={() => onChange({ page: page.page - 1 })}
      >
        Previous
      </button>
      <span className="muted">
        {page.total} payments, page {page.page + 1} of {Math.max(page.pages, 1)}
      </span>
      <button
        type="button"
        disabled={page.page + 1 >= page.pages}
        onClick={() => onChange({ page: page.page + 1 })}
      >
        Next
      </button>
    </nav>
  );
}

function Filtering({
  filters,
  onChange,
}: {
  filters: Filters;
  onChange: (next: Partial<Filters>) => void;
}) {
  return (
    <form className="filters" onSubmit={(event) => event.preventDefault()}>
      <fieldset>
        <legend>Status</legend>
        {EVERY_STATUS.map((status) => (
          <label key={status}>
            <input
              type="checkbox"
              checked={filters.status.includes(status)}
              onChange={(event) =>
                onChange({
                  status: event.target.checked
                    ? [...filters.status, status]
                    : filters.status.filter((chosen) => chosen !== status),
                })
              }
            />
            {readable(status)}
          </label>
        ))}
      </fieldset>

      <fieldset>
        <legend>Risk</legend>
        {EVERY_VERDICT.map((verdict) => (
          <label key={verdict}>
            <input
              type="checkbox"
              checked={filters.riskVerdict.includes(verdict)}
              onChange={(event) =>
                onChange({
                  riskVerdict: event.target.checked
                    ? [...filters.riskVerdict, verdict]
                    : filters.riskVerdict.filter((chosen) => chosen !== verdict),
                })
              }
            />
            {readable(verdict)}
          </label>
        ))}
      </fieldset>

      <div className="range">
        <label htmlFor="minAmount">From amount</label>
        <input
          id="minAmount"
          inputMode="decimal"
          value={filters.minAmount}
          onChange={(event) => onChange({ minAmount: event.target.value })}
        />
        <label htmlFor="maxAmount">to</label>
        <input
          id="maxAmount"
          inputMode="decimal"
          value={filters.maxAmount}
          onChange={(event) => onChange({ maxAmount: event.target.value })}
        />
        <label htmlFor="currency" className="sr-only">
          Currency
        </label>
        <select
          id="currency"
          value={filters.currency}
          onChange={(event) => onChange({ currency: event.target.value })}
        >
          {["TRY", "USD", "EUR", "GBP", "JPY"].map((currency) => (
            <option key={currency}>{currency}</option>
          ))}
        </select>
      </div>

      <div className="range">
        <label htmlFor="from">From date</label>
        <input
          id="from"
          type="date"
          value={filters.from}
          onChange={(event) => onChange({ from: event.target.value })}
        />
        <label htmlFor="to">to</label>
        <input
          id="to"
          type="date"
          value={filters.to}
          onChange={(event) => onChange({ to: event.target.value })}
        />
      </div>

      <div className="range">
        <label htmlFor="reference">Your reference</label>
        <input
          id="reference"
          value={filters.reference}
          onChange={(event) => onChange({ reference: event.target.value })}
        />
        <label htmlFor="size">Per page</label>
        <select
          id="size"
          value={String(filters.size)}
          onChange={(event) => onChange({ size: Number(event.target.value) })}
        >
          {[DEFAULT_SIZE, 50, 100].map((size) => (
            <option key={size}>{size}</option>
          ))}
        </select>
      </div>
    </form>
  );
}

function when(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
