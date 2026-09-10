import { describe, expect, it } from "vitest";
import { callerIn } from "./accessToken";

function tokenSaying(claims: Record<string, unknown>): string {
  const payload = btoa(JSON.stringify(claims))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${payload}.signature`;
}

describe("reading an access token", () => {
  it("finds who is signed in and what they hold", () => {
    const caller = callerIn(
      tokenSaying({
        sub: "0d1f2e3a-0000-4000-8000-000000000001",
        merchant: "0d1f2e3a-0000-4000-8000-000000000002",
        roles: ["OWNER", "ANALYST"],
        exp: 1_800_000_000,
      }),
    );

    expect(caller?.userId).toBe("0d1f2e3a-0000-4000-8000-000000000001");
    expect(caller?.merchantId).toBe("0d1f2e3a-0000-4000-8000-000000000002");
    expect(caller?.roles).toEqual(["OWNER", "ANALYST"]);
    expect(caller?.expiresAt.toISOString()).toBe("2027-01-15T08:00:00.000Z");
  });

  it("reads base64url, which is what the platform actually issues", () => {
    // A payload whose base64 contains the two characters that differ, and needs padding.
    const caller = callerIn(
      tokenSaying({ sub: "a>b?c~d", merchant: "m", roles: [], exp: 1 }),
    );
    expect(caller?.userId).toBe("a>b?c~d");
  });

  it("gives up rather than guessing", () => {
    expect(callerIn("not-a-token")).toBeNull();
    expect(callerIn("a.b.c")).toBeNull();
    expect(callerIn(tokenSaying({ sub: "someone" }))).toBeNull();
    expect(callerIn(tokenSaying({ merchant: "m", exp: 1 }))).toBeNull();
  });

  it("ignores a role that is not a name", () => {
    const caller = callerIn(
      tokenSaying({ sub: "s", merchant: "m", roles: ["OWNER", 7, null], exp: 1 }),
    );
    expect(caller?.roles).toEqual(["OWNER"]);
  });
});
