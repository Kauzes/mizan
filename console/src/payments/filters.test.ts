import { describe, expect, it } from "vitest";
import {
  DEFAULT_SIZE,
  NOTHING_ASKED,
  describe as describeFilters,
  isEverything,
  readFilters,
  toQuery,
  toSearchParams,
} from "./filters";

describe("what is in the URL", () => {
  it("survives being written and read back", () => {
    const asked = {
      ...NOTHING_ASKED,
      status: ["CAPTURED", "DECLINED"],
      riskVerdict: ["REVIEW"],
      minAmount: "10.00",
      maxAmount: "500.00",
      currency: "USD",
      from: "2026-03-01",
      to: "2026-03-31",
      reference: "order-9",
      page: 2,
    };

    expect(readFilters(toSearchParams(asked))).toEqual(asked);
  });

  it("says nothing about what was not asked", () => {
    // A link a merchant sends somebody should carry their search and not a row of defaults
    // that look like decisions.
    expect(toSearchParams(NOTHING_ASKED).toString()).toBe("");
  });

  it("ignores a page number somebody typed into the address bar", () => {
    expect(readFilters(new URLSearchParams("page=-4")).page).toBe(0);
    expect(readFilters(new URLSearchParams("page=lots")).page).toBe(0);
    expect(readFilters(new URLSearchParams("size=")).size).toBe(DEFAULT_SIZE);
  });
});

describe("what the platform is asked", () => {
  it("turns typed amounts into minor units", () => {
    const query = new URLSearchParams(
      toQuery({ ...NOTHING_ASKED, minAmount: "12.34", maxAmount: "1000", currency: "TRY" }),
    );

    expect(query.get("minAmount")).toBe("1234");
    expect(query.get("maxAmount")).toBe("100000");
    // Without a currency an amount range compares minor units across currencies, which is
    // arithmetic on two different things.
    expect(query.get("currency")).toBe("TRY");
  });

  it("knows a currency without decimal places", () => {
    const query = new URLSearchParams(
      toQuery({ ...NOTHING_ASKED, minAmount: "1250", currency: "JPY" }),
    );
    expect(query.get("minAmount")).toBe("1250");
  });

  it("leaves an amount out rather than sending nonsense", () => {
    const query = new URLSearchParams(
      toQuery({ ...NOTHING_ASKED, minAmount: "half of it" }),
    );
    expect(query.has("minAmount")).toBe(false);
  });

  it("asks for whole local days, with the end after the last one", () => {
    const query = new URLSearchParams(
      toQuery({ ...NOTHING_ASKED, from: "2026-03-01", to: "2026-03-01" }),
    );

    const from = new Date(query.get("from") ?? "");
    const to = new Date(query.get("to") ?? "");

    // One day asked for, and the range is the whole of it: midnight to the next midnight.
    // The API treats the end as exclusive, which is what stops a payment at midnight from
    // appearing on two consecutive days.
    expect(to.getTime() - from.getTime()).toBe(24 * 60 * 60 * 1000);
    expect(new Date(`2026-03-01T00:00:00`).toISOString()).toBe(query.get("from"));
  });

  it("always says which page it wants", () => {
    const query = new URLSearchParams(toQuery(NOTHING_ASKED));
    expect(query.get("page")).toBe("0");
    expect(query.get("size")).toBe(String(DEFAULT_SIZE));
  });
});

describe("explaining an empty answer", () => {
  it("knows the difference between no payments and no matches", () => {
    expect(isEverything(NOTHING_ASKED)).toBe(true);
    expect(isEverything({ ...NOTHING_ASKED, page: 3, size: 100 })).toBe(true);
    expect(isEverything({ ...NOTHING_ASKED, reference: "order-1" })).toBe(false);
  });

  it("says what was asked, in words a merchant used", () => {
    const said = describeFilters({
      ...NOTHING_ASKED,
      status: ["HELD_FOR_REVIEW"],
      minAmount: "10.00",
      reference: "order-9",
    });

    expect(said).toEqual([
      "status held for review",
      "at least 10.00 TRY",
      "reference order-9",
    ]);
  });
});
