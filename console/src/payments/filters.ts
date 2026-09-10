import { parseMoney } from "../money/money";

/**
 * What a merchant is looking at, held in the URL rather than in a component.
 *
 * The URL is the state. Not a nicety: a merchant asked "what happened to this payment" sends
 * somebody a link, and a link that reopens an empty search is a link that answers nothing.
 * It also means the back button works, which is the one piece of navigation nobody has to be
 * taught.
 */

export interface Filters {
  readonly status: readonly string[];
  readonly riskVerdict: readonly string[];
  readonly currency: string;
  /** As a person types them: major units, not minor. Converted on the way to the platform. */
  readonly minAmount: string;
  readonly maxAmount: string;
  /** Dates as a date input gives them: YYYY-MM-DD, meant as whole local days. */
  readonly from: string;
  readonly to: string;
  readonly reference: string;
  readonly page: number;
  readonly size: number;
}

export const EVERY_STATUS = [
  "CREATED",
  "AUTHORIZED",
  "HELD_FOR_REVIEW",
  "DECLINED",
  "AUTHORIZATION_UNKNOWN",
  "CAPTURED",
  "VOIDED",
] as const;

export const EVERY_VERDICT = ["APPROVE", "REVIEW", "BLOCK", "UNAVAILABLE"] as const;

export const DEFAULT_SIZE = 25;

export const NOTHING_ASKED: Filters = {
  status: [],
  riskVerdict: [],
  currency: "TRY",
  minAmount: "",
  maxAmount: "",
  from: "",
  to: "",
  reference: "",
  page: 0,
  size: DEFAULT_SIZE,
};

export function readFilters(params: URLSearchParams): Filters {
  return {
    status: params.getAll("status"),
    riskVerdict: params.getAll("riskVerdict"),
    currency: params.get("currency") ?? NOTHING_ASKED.currency,
    minAmount: params.get("minAmount") ?? "",
    maxAmount: params.get("maxAmount") ?? "",
    from: params.get("from") ?? "",
    to: params.get("to") ?? "",
    reference: params.get("reference") ?? "",
    page: positive(params.get("page"), 0),
    size: positive(params.get("size"), DEFAULT_SIZE),
  };
}

/** What goes in the address bar: only what was actually asked for. */
export function toSearchParams(filters: Filters): URLSearchParams {
  const params = new URLSearchParams();
  for (const status of filters.status) {
    params.append("status", status);
  }
  for (const verdict of filters.riskVerdict) {
    params.append("riskVerdict", verdict);
  }
  if (filters.minAmount || filters.maxAmount) {
    params.set("currency", filters.currency);
  }
  for (const [name, value] of [
    ["minAmount", filters.minAmount],
    ["maxAmount", filters.maxAmount],
    ["from", filters.from],
    ["to", filters.to],
    ["reference", filters.reference.trim()],
  ] as const) {
    if (value) {
      params.set(name, value);
    }
  }
  if (filters.page > 0) {
    params.set("page", String(filters.page));
  }
  if (filters.size !== DEFAULT_SIZE) {
    params.set("size", String(filters.size));
  }
  return params;
}

/**
 * What the platform is asked, which is not quite what the URL says.
 *
 * Two translations happen here and nowhere else. Amounts become minor units, because that is
 * the only thing the API speaks. And a date becomes an instant: a merchant asking for "the
 * first of March" means their whole day, so the end is the start of the next one — the API
 * treats it as exclusive, which is what stops a payment at midnight appearing on both days.
 */
export function toQuery(filters: Filters): string {
  const params = new URLSearchParams();

  for (const status of filters.status) {
    params.append("status", status);
  }
  for (const verdict of filters.riskVerdict) {
    params.append("riskVerdict", verdict);
  }

  const min = filters.minAmount ? parseMoney(filters.minAmount, filters.currency) : null;
  const max = filters.maxAmount ? parseMoney(filters.maxAmount, filters.currency) : null;
  if (min !== null || max !== null) {
    params.set("currency", filters.currency);
  }
  if (min !== null) {
    params.set("minAmount", String(min));
  }
  if (max !== null) {
    params.set("maxAmount", String(max));
  }

  if (filters.from) {
    params.set("from", startOfDay(filters.from));
  }
  if (filters.to) {
    params.set("to", startOfDayAfter(filters.to));
  }
  if (filters.reference.trim()) {
    params.set("reference", filters.reference.trim());
  }

  params.set("page", String(filters.page));
  params.set("size", String(filters.size));
  return params.toString();
}

/** Whether anything was asked for at all, which decides what an empty answer means. */
export function isEverything(filters: Filters): boolean {
  return (
    filters.status.length === 0 &&
    filters.riskVerdict.length === 0 &&
    !filters.minAmount &&
    !filters.maxAmount &&
    !filters.from &&
    !filters.to &&
    !filters.reference.trim()
  );
}

/**
 * The filters in words, for the page that has to explain an empty answer.
 *
 * A merchant who filtered themselves into nothing and a merchant who has never taken a
 * payment see the same blank table otherwise, and only one of them has a problem they can fix.
 */
export function describe(filters: Filters): string[] {
  const said: string[] = [];
  if (filters.status.length) {
    said.push(`status ${filters.status.map(readable).join(" or ")}`);
  }
  if (filters.riskVerdict.length) {
    said.push(`risk said ${filters.riskVerdict.map(readable).join(" or ")}`);
  }
  if (filters.minAmount && filters.maxAmount) {
    said.push(`between ${filters.minAmount} and ${filters.maxAmount} ${filters.currency}`);
  } else if (filters.minAmount) {
    said.push(`at least ${filters.minAmount} ${filters.currency}`);
  } else if (filters.maxAmount) {
    said.push(`at most ${filters.maxAmount} ${filters.currency}`);
  }
  if (filters.from && filters.to) {
    said.push(`between ${filters.from} and ${filters.to}`);
  } else if (filters.from) {
    said.push(`on or after ${filters.from}`);
  } else if (filters.to) {
    said.push(`on or before ${filters.to}`);
  }
  if (filters.reference.trim()) {
    said.push(`reference ${filters.reference.trim()}`);
  }
  return said;
}

export function readable(name: string): string {
  return name.toLowerCase().replace(/_/g, " ");
}

function positive(value: string | null, fallback: number): number {
  if (value === null || value.trim() === "") {
    return fallback;
  }
  const parsed = Number(value);
  // Number("") is zero, which would turn an empty parameter into page zero of size zero.
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

/**
 * A date the merchant chose, as the instant their day starts.
 *
 * Local, deliberately. A merchant in Istanbul asking for the first of March means their first
 * of March, and answering with a UTC day would quietly shift the boundary by three hours and
 * put the evening's payments on the wrong day.
 */
function startOfDay(date: string): string {
  return new Date(`${date}T00:00:00`).toISOString();
}

function startOfDayAfter(date: string): string {
  const next = new Date(`${date}T00:00:00`);
  next.setDate(next.getDate() + 1);
  return next.toISOString();
}
