import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { fetchPage, type Page } from "../api/paged";
import { PlatformError } from "../api/problems";
import { formatMoney } from "../money/money";
import type { Entry } from "../payments/types";
import { useSession } from "../session/SessionProvider";

/**
 * The books, and the fact that they balance.
 *
 * The ledger is the most careful thing on this platform and, until this page, the least
 * visible. Everything here is read only and stays that way: the books are written by things
 * that moved money, and an entry posted by hand is exactly what double entry exists to make
 * impossible to hide.
 *
 * Nothing on this page is added up in the browser. A balance is the ledger's own figure, and
 * a second implementation of the arithmetic would be a second thing that can be wrong —
 * silently, and about money. The one sum that is computed here is each entry's postings, and
 * that is computed precisely so that a wrong one would show.
 */
interface Account {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly type: string;
  readonly normalSide: string;
  readonly currency: string;
  readonly balance: number;
}

export function Books() {
  const { session, caller, can } = useSession();
  const [params, setParams] = useSearchParams();
  const merchantId = caller?.merchantId;
  const chosen = params.get("accountId");

  const [accounts, setAccounts] = useState<readonly Account[] | null>(null);
  const [entries, setEntries] = useState<Page<Entry> | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);

  useEffect(() => {
    if (!merchantId || !can("ENTRY_READ")) {
      return;
    }
    let current = true;

    session
      .json<Account[]>(`/api/v1/merchants/${merchantId}/accounts`)
      .then((theirs) => {
        if (current) {
          setAccounts(theirs);
        }
      })
      .catch((failed: unknown) => current && setRefusal(said(failed)));

    return () => {
      current = false;
    };
  }, [session, merchantId, can]);

  useEffect(() => {
    if (!merchantId || !can("ENTRY_READ")) {
      return;
    }
    let current = true;
    setRefusal(null);

    const query = new URLSearchParams({ size: "25" });
    if (chosen) {
      query.set("accountId", chosen);
    }

    fetchPage<Entry>(session, `/api/v1/merchants/${merchantId}/entries?${query}`)
      .then((page) => current && setEntries(page))
      .catch((failed: unknown) => current && setRefusal(said(failed)));

    return () => {
      current = false;
    };
  }, [session, merchantId, can, chosen]);

  if (!can("ENTRY_READ")) {
    return (
      <main>
        <h1>The books</h1>
        <p className="muted">This account may not read the books.</p>
      </main>
    );
  }

  return (
    <main>
      <h1>The books</h1>

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}

      <h2>Accounts</h2>
      {accounts === null ? <p className="muted">Looking…</p> : null}
      {accounts?.length === 0 ? (
        <p className="muted">
          No accounts yet. The ledger opens none on anybody's behalf, so the first one is
          opened deliberately.
        </p>
      ) : null}

      {accounts && accounts.length > 0 ? (
        <table className="accounts">
          <thead>
            <tr>
              <th scope="col">Account</th>
              <th scope="col">Kind</th>
              <th scope="col" className="right">
                Balance
              </th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((account) => (
              <tr key={account.id} className={chosen === account.id ? "chosen" : undefined}>
                <td>
                  <button
                    type="button"
                    className="as-link"
                    onClick={() =>
                      setParams(chosen === account.id ? {} : { accountId: account.id })
                    }
                  >
                    <code>{account.code}</code>
                  </button>{" "}
                  <span className="muted">{account.name}</span>
                </td>
                <td className="muted">
                  {account.type.toLowerCase()}, {account.normalSide.toLowerCase()} positive
                </td>
                <td className="right">
                  {formatMoney({ amount: account.balance, currency: account.currency })}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      <h2>
        {chosen ? "Entries that touched this account" : "Entries"}
        {chosen ? (
          <>
            {" "}
            <button type="button" className="as-link" onClick={() => setParams({})}>
              show all
            </button>
          </>
        ) : null}
      </h2>

      {entries === null ? <p className="muted">Looking…</p> : null}
      {entries?.items.length === 0 ? (
        <p className="muted">Nothing has been written here yet.</p>
      ) : null}

      {entries?.items.map((entry) => (
        <Written key={entry.id} entry={entry} />
      ))}

      {entries && entries.total > entries.items.length ? (
        <p className="muted">
          {entries.items.length} of {entries.total} entries.
        </p>
      ) : null}
    </main>
  );
}

/** One entry, its postings, and the zero they add up to. */
function Written({ entry }: { entry: Entry }) {
  const currency = entry.postings[0]?.currency ?? "TRY";
  const sum = entry.postings.reduce((total, posting) => total + posting.amount, 0);
  const caused = causedBy(entry.externalReference);

  return (
    <div className="entry">
      <p>
        <span className="muted">{new Date(entry.occurredAt).toLocaleString()}</span>{" "}
        {entry.description}
        {caused ? (
          <>
            {" · "}
            <Link to={`/payments/${caused}`}>the payment</Link>
          </>
        ) : null}
      </p>
      <p className="muted">
        <code>{entry.externalReference}</code>
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
            <td className="right">{formatMoney({ amount: sum, currency })}</td>
            <td className="muted">every entry, always</td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

/**
 * The payment an entry names, if it names one.
 *
 * The external reference is how an entry says what caused it: `payment:<id>:capture` and
 * `refund:<id>:<reference>`. Reading it here rather than storing a second link is the point
 * of it being a reference at all — one fact, written once, in the entry the ledger keeps.
 */
function causedBy(externalReference: string): string | null {
  const parts = externalReference.split(":");
  return (parts[0] === "payment" || parts[0] === "refund") && parts[1] ? parts[1] : null;
}

function said(failed: unknown): string {
  return failed instanceof PlatformError
    ? failed.problem.detail
    : "This platform could not be reached.";
}
