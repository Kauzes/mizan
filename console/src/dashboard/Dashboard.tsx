import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { PlatformError } from "../api/problems";
import { formatMoney } from "../money/money";
import { useSession } from "../session/SessionProvider";
import { Daily, type Day } from "./Daily";

/**
 * Where a merchant lands, and the only page here about many payments rather than one.
 *
 * The question it answers is "does today look like yesterday, and if not, why" — so every
 * figure on it is one the database worked out, and every rate has its volume beside it.
 */
interface Summary {
  readonly from: string;
  readonly to: string;
  readonly totals: {
    readonly created: number;
    readonly attempted: number;
    readonly authorized: number;
    readonly captured: number;
    readonly declinedByAcquirer: number;
    readonly refusedByPlatform: number;
    readonly held: number;
    readonly unknown: number;
  };
  /** Null rather than zero when nothing was attempted. Those are different facts. */
  readonly authorizationRate: number | null;
  readonly volume: ReadonlyArray<{
    readonly currency: string;
    readonly captured: number;
    readonly refunded: number;
    readonly payments: number;
  }>;
  readonly byDay: readonly Day[];
  readonly refusals: ReadonlyArray<{
    readonly by: string;
    readonly reason: string;
    readonly payments: number;
  }>;
  readonly needsSomebody: {
    readonly waitingForAPerson: number;
    readonly needingAnOperator: number;
  };
}

const RANGES = [
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
  { days: 90, label: "90 days" },
] as const;

export function Dashboard() {
  const { session, caller, can } = useSession();
  const [params, setParams] = useSearchParams();
  const merchantId = caller?.merchantId;

  const days = Number(params.get("days")) || 30;
  const [summary, setSummary] = useState<Summary | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);

  useEffect(() => {
    if (!merchantId) {
      return;
    }
    let current = true;
    setRefusal(null);

    const to = new Date();
    const from = new Date(to);
    from.setDate(from.getDate() - days);

    const query = new URLSearchParams({
      from: from.toISOString(),
      to: to.toISOString(),
      // The merchant's own days. Bucketing in UTC would put three hours of an Istanbul
      // Tuesday on Monday.
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });

    session
      .json<Summary>(`/api/v1/merchants/${merchantId}/summary?${query}`)
      .then((answer) => current && setSummary(answer))
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
  }, [session, merchantId, days]);

  return (
    <main>
      <h1>How business is</h1>

      <nav className="ranges" aria-label="How far back">
        {RANGES.map((range) => (
          <button
            key={range.days}
            type="button"
            className={range.days === days ? "chosen" : undefined}
            onClick={() => setParams({ days: String(range.days) })}
          >
            {range.label}
          </button>
        ))}
      </nav>

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}

      {summary === null && !refusal ? <p className="muted">Looking…</p> : null}

      {summary ? <Body summary={summary} canRule={can("REVIEW_RULE")} /> : null}
    </main>
  );
}

function Body({ summary, canRule }: { summary: Summary; canRule: boolean }) {
  const { totals, authorizationRate: rate } = summary;

  return (
    <>
      <NeedsSomebody needs={summary.needsSomebody} canRule={canRule} />

      {totals.created === 0 ? (
        // Not a row of zeroes. A merchant who has taken no payments and a platform that has
        // stopped working look the same otherwise, and only one of them is a problem.
        <p className="muted">
          No payments in this period. Nothing has gone wrong — there is simply nothing to
          count yet.
        </p>
      ) : (
        <>
          <div className="tiles">
            <Tile
              figure={rate === null ? "—" : `${Math.round(rate * 100)}%`}
              label="went through"
              beside={
                rate === null
                  ? "nothing was attempted"
                  : `${totals.authorized} of ${totals.attempted} attempted`
              }
            />
            <Tile
              figure={String(totals.captured)}
              label="captured"
              beside={`${totals.created - totals.attempted} still only an intent`}
            />
            <Tile
              figure={String(totals.declinedByAcquirer + totals.refusedByPlatform)}
              label="refused"
              beside={`${totals.declinedByAcquirer} by the acquirer, ${totals.refusedByPlatform} here`}
            />
          </div>

          <h2>Money</h2>
          {summary.volume.length === 0 ? (
            <p className="muted">Nothing has been captured in this period.</p>
          ) : (
            <ul className="plain">
              {summary.volume.map((money) => (
                <li key={money.currency}>
                  <strong>
                    {formatMoney({ amount: money.captured, currency: money.currency })}
                  </strong>{" "}
                  <span className="muted">
                    across {money.payments} payments
                    {money.refunded > 0
                      ? `, of which ${formatMoney({
                          amount: money.refunded,
                          currency: money.currency,
                        })} given back`
                      : ""}
                  </span>
                </li>
              ))}
            </ul>
          )}

          <h2>Day by day</h2>
          <Daily days={summary.byDay} />
          <Table days={summary.byDay} />

          <h2>Why payments did not go through</h2>
          <Refusals refusals={summary.refusals} unknown={totals.unknown} />
        </>
      )}
    </>
  );
}

/** A figure, what it is, and the number it is a fraction of. */
function Tile({
  figure,
  label,
  beside,
}: {
  figure: string;
  label: string;
  beside: string;
}) {
  return (
    <div className="tile">
      <p className="figure">{figure}</p>
      <p className="label">{label}</p>
      <p className="muted">{beside}</p>
    </div>
  );
}

/**
 * What is outstanding, and a way to go and deal with it.
 *
 * Above everything else on the page, and not scoped to the range: somebody waiting is the
 * only thing here that is about right now rather than about how last month went.
 */
function NeedsSomebody({
  needs,
  canRule,
}: {
  needs: Summary["needsSomebody"];
  canRule: boolean;
}) {
  if (needs.waitingForAPerson === 0 && needs.needingAnOperator === 0) {
    return <p className="muted">Nothing is waiting for anybody.</p>;
  }

  return (
    <div className="needs" role="status">
      {needs.waitingForAPerson > 0 ? (
        <p>
          <strong>{needs.waitingForAPerson}</strong>{" "}
          {needs.waitingForAPerson === 1 ? "payment is" : "payments are"} held, waiting for a
          person to rule on {needs.waitingForAPerson === 1 ? "it" : "them"}.{" "}
          {canRule ? <Link to="/reviews">The review queue</Link> : "An analyst can release them."}
        </p>
      ) : null}
      {needs.needingAnOperator > 0 ? (
        <p>
          <strong>{needs.needingAnOperator}</strong>{" "}
          {needs.needingAnOperator === 1 ? "payment needs" : "payments need"} an operator: this
          platform could not find out what happened to{" "}
          {needs.needingAnOperator === 1 ? "it" : "them"}.{" "}
          <Link to="/payments?status=AUTHORIZATION_UNKNOWN">Those payments</Link>
        </p>
      ) : null}
    </div>
  );
}

/**
 * The refusals, split by who refused.
 *
 * The acquirer's own words are repeated rather than translated: a merchant asking their
 * customer's bank about a decline needs the reason that bank gave, not this platform's
 * paraphrase of it.
 */
function Refusals({
  refusals,
  unknown,
}: {
  refusals: Summary["refusals"];
  unknown: number;
}) {
  const acquirer = refusals.filter((one) => one.by === "ACQUIRER");
  const platform = refusals.filter((one) => one.by === "PLATFORM");

  if (refusals.length === 0 && unknown === 0) {
    return <p className="muted">Nothing was refused in this period.</p>;
  }

  return (
    <div className="refusals">
      <div>
        <h3>By the acquirer</h3>
        {acquirer.length === 0 ? (
          <p className="muted">None.</p>
        ) : (
          <ul className="plain">
            {acquirer.map((one) => (
              <li key={one.reason}>
                <strong>{one.payments}</strong> <code>{one.reason}</code>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div>
        <h3>By this platform</h3>
        {platform.length === 0 ? (
          <p className="muted">None.</p>
        ) : (
          <ul className="plain">
            {platform.map((one) => (
              <li key={one.reason}>
                <strong>{one.payments}</strong> <span className="muted">{one.reason}</span>
              </li>
            ))}
          </ul>
        )}
        {unknown > 0 ? (
          <p className="muted">
            And {unknown} this platform never got an answer about, which is neither.
          </p>
        ) : null}
      </div>
    </div>
  );
}

/**
 * The same figures as a table.
 *
 * Not a fallback. Somebody who cannot hover, cannot see the colour, or wants to copy the
 * numbers into a spreadsheet is not a lesser reader of this page.
 */
function Table({ days }: { days: readonly Day[] }) {
  const [open, setOpen] = useState(false);
  const counted = days.filter((day) => day.attempted > 0);

  if (counted.length === 0) {
    return null;
  }

  return (
    <>
      <p>
        <button type="button" className="as-link" onClick={() => setOpen(!open)}>
          {open ? "Hide the figures" : "Show the figures"}
        </button>
      </p>
      {open ? (
        <table className="figures">
          <thead>
            <tr>
              <th scope="col">Day</th>
              <th scope="col" className="right">
                Tried
              </th>
              <th scope="col" className="right">
                Authorized
              </th>
              <th scope="col" className="right">
                Captured
              </th>
              <th scope="col" className="right">
                Declined
              </th>
              <th scope="col" className="right">
                Held
              </th>
            </tr>
          </thead>
          <tbody>
            {counted.map((day) => (
              <tr key={day.day}>
                <td>{day.day}</td>
                <td className="right">{day.attempted}</td>
                <td className="right">{day.authorized}</td>
                <td className="right">{day.captured}</td>
                <td className="right">{day.declined}</td>
                <td className="right">{day.held}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </>
  );
}
