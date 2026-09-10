/**
 * Turning what the platform transports into what a person reads.
 *
 * The API deals in minor units and only ever in minor units: 125000 is one thousand two
 * hundred and fifty lira, and there is no decimal point anywhere in the wire format. That is
 * the right choice on the server, where a decimal is a rounding bug waiting for a busy day.
 * It makes this file the one place the platform's numbers become money, and the reason no
 * component is allowed to do its own arithmetic on an amount.
 */

/** An amount as the platform speaks it: minor units, and what they are units of. */
export interface Money {
  readonly amount: number;
  readonly currency: string;
}

/**
 * How many minor units make one of the major unit, for a currency.
 *
 * Asked of the browser rather than kept in a table here. Two decimal places is the common
 * case and not the only one — the yen has none and the dinar has three — and a table of
 * exceptions maintained in a console is a table that is wrong about somebody's currency.
 */
export function minorUnitDigits(currency: string): number {
  const format = new Intl.NumberFormat("en", { style: "currency", currency });
  // Always present for a currency format; typed as optional because the same options object
  // describes formats that have no such thing.
  return format.resolvedOptions().maximumFractionDigits ?? 2;
}

/**
 * The amount, written the way somebody would say it, with its currency.
 *
 * The division looks like the floating point mistake this file exists to avoid, and is not
 * one: minor units are integers, every integer below 2^53 is exact in a double, and the
 * nearest double to `amount / 10^digits` rounds back to exactly that decimal when it is
 * rounded to `digits` places, which is what the formatter then does. What would be a mistake
 * is doing arithmetic on the result, which is why nothing here returns a number.
 *
 * An amount too large for that to hold throws rather than quietly showing the wrong figure.
 * A console that is wrong about money is worse than one that is broken about it.
 */
export function formatMoney(money: Money, locale?: string): string {
  if (!Number.isSafeInteger(money.amount)) {
    throw new RangeError(
      `${money.amount} is not a whole number of minor units this can format exactly`,
    );
  }

  const digits = minorUnitDigits(money.currency);
  return new Intl.NumberFormat(locale ?? localeOfThisBrowser(), {
    style: "currency",
    currency: money.currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(money.amount / 10 ** digits);
}

/**
 * What a person typed, as minor units, or null if it was not an amount.
 *
 * The mirror image of the above and the half that actually has to be careful: a person typing
 * 12.34 means 1234, and reaching that by multiplying a parsed float by a hundred gives 1233
 * often enough to matter. So the digits are counted as text and never multiplied.
 */
export function parseMoney(typed: string, currency: string): number | null {
  const digits = minorUnitDigits(currency);
  const cleaned = typed.trim().replace(/\s/g, "");

  const parts = /^(-?)(\d*)(?:[.,](\d*))?$/.exec(cleaned);
  if (!parts || (parts[2] === "" && (parts[3] ?? "") === "")) {
    return null;
  }

  const [, sign = "", major = "", minor = ""] = parts;
  if (minor.length > digits) {
    // Not rounded silently. Somebody who typed 1.005 into a lira field either meant something
    // this currency cannot express or made a mistake, and both deserve to be told.
    return null;
  }

  const combined = `${major || "0"}${minor.padEnd(digits, "0")}`;
  const amount = Number(combined);
  return Number.isSafeInteger(amount) ? (sign === "-" ? -amount : amount) : null;
}

function localeOfThisBrowser(): string {
  return typeof navigator === "undefined" ? "en" : navigator.language;
}
