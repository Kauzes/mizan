import { describe, expect, it } from "vitest";
import { formatMoney, minorUnitDigits, parseMoney } from "./money";

describe("showing an amount", () => {
  it("reads minor units as money", () => {
    expect(formatMoney({ amount: 125000, currency: "TRY" }, "en-US")).toContain("1,250.00");
    expect(formatMoney({ amount: 1, currency: "TRY" }, "en-US")).toContain("0.01");
    expect(formatMoney({ amount: 0, currency: "TRY" }, "en-US")).toContain("0.00");
  });

  it("knows that not every currency has two decimal places", () => {
    // The failure this prevents is a hundredfold one: showing 1250 yen as 12.50.
    expect(minorUnitDigits("JPY")).toBe(0);
    expect(formatMoney({ amount: 1250, currency: "JPY" }, "en-US")).toContain("1,250");
    expect(formatMoney({ amount: 1250, currency: "JPY" }, "en-US")).not.toContain("12.50");

    expect(minorUnitDigits("KWD")).toBe(3);
    expect(formatMoney({ amount: 1250, currency: "KWD" }, "en-US")).toContain("1.250");
  });

  it("stays exact at amounts nobody expects to see", () => {
    // The one a double would get wrong if this multiplied and divided its way there.
    expect(formatMoney({ amount: 123456789, currency: "TRY" }, "en-US")).toContain(
      "1,234,567.89",
    );
    expect(formatMoney({ amount: -2500, currency: "TRY" }, "en-US")).toContain("25.00");
  });

  it("refuses an amount it cannot be exact about", () => {
    expect(() => formatMoney({ amount: 2 ** 53, currency: "TRY" })).toThrow(RangeError);
    expect(() => formatMoney({ amount: 12.5, currency: "TRY" })).toThrow(RangeError);
  });
});

describe("reading an amount somebody typed", () => {
  it("counts the digits rather than multiplying", () => {
    expect(parseMoney("12.34", "TRY")).toBe(1234);
    expect(parseMoney("0.07", "TRY")).toBe(7);
    expect(parseMoney("1250", "TRY")).toBe(125000);
    expect(parseMoney(".5", "TRY")).toBe(50);
    expect(parseMoney("12,34", "TRY")).toBe(1234);
    expect(parseMoney(" 1 2 . 3 4 ", "TRY")).toBe(1234);
  });

  it("and gets the case a float gets wrong", () => {
    // 8.29 * 100 is 828.9999999999999 in a double, and Math.round hides that until it does
    // not. This never has a float to round.
    expect(parseMoney("8.29", "TRY")).toBe(829);
    expect(parseMoney("1.005", "KWD")).toBe(1005);
  });

  it("refuses more precision than the currency has", () => {
    expect(parseMoney("1.005", "TRY")).toBeNull();
    expect(parseMoney("100.5", "JPY")).toBeNull();
  });

  it("refuses what is not an amount", () => {
    expect(parseMoney("", "TRY")).toBeNull();
    expect(parseMoney("lots", "TRY")).toBeNull();
    expect(parseMoney("1e3", "TRY")).toBeNull();
    expect(parseMoney("--1", "TRY")).toBeNull();
  });
});
