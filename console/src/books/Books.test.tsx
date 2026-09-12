import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Books } from "./Books";

const MERCHANT = "22222222-2222-4222-8222-222222222222";
const ACCOUNT = "44444444-4444-4444-8444-444444444444";
const PAYMENT = "33333333-3333-4333-8333-333333333333";

function token(roles: string[]): string {
  const payload = btoa(
    JSON.stringify({
      sub: "11111111-1111-4111-8111-111111111111",
      merchant: MERCHANT,
      roles,
      exp: Math.floor(Date.now() / 1000) + 900,
    }),
  )
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${payload}.signature`;
}

const ENTRY = {
  id: "55555555-5555-4555-8555-555555555555",
  externalReference: `payment:${PAYMENT}:capture`,
  description: "Card payment captured, order-1",
  occurredAt: "2026-03-01T10:00:00Z",
  postings: [
    { accountCode: "platform.clearing.try", amount: 125000, currency: "TRY", direction: "DEBIT" },
    { accountCode: "settlement.try", amount: -125000, currency: "TRY", direction: "CREDIT" },
  ],
};

function platform(roles: string[], entry: unknown = ENTRY) {
  const asked: string[] = [];

  const fetchImpl = vi.fn(async (path: string) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({ accessToken: token(roles), tokenType: "Bearer", expiresIn: 900 });
    }
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: { OWNER: ["ENTRY_READ"], ANALYST: [] },
        permissions: ["ENTRY_READ"],
      });
    }

    asked.push(path);

    if (path.includes("/accounts")) {
      return Response.json([
        {
          id: ACCOUNT,
          code: "settlement.try",
          name: "Owed to the merchant, TRY",
          type: "LIABILITY",
          normalSide: "CREDIT",
          currency: "TRY",
          balance: -125000,
        },
      ]);
    }
    return new Response(JSON.stringify([entry]), {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "X-Total-Count": "1",
        "X-Page": "0",
        "X-Page-Size": "25",
      },
    });
  }) as unknown as typeof fetch;

  return { fetchImpl, asked };
}

function show(fetchImpl: typeof fetch, at = "/books") {
  render(
    <SessionProvider session={createSession(fetchImpl)}>
      <MemoryRouter initialEntries={[at]}>
        <Books />
      </MemoryRouter>
    </SessionProvider>,
  );
}

describe("the books", () => {
  it("shows a balance as the ledger's own figure", async () => {
    show(platform(["OWNER"]).fetchImpl);

    expect((await screen.findAllByText("settlement.try")).length).toBeGreaterThan(0);
    // Not flipped to read naturally for a liability, and not recomputed here: the type says
    // which way to read it, and a second implementation of the arithmetic is a second thing
    // that can be wrong about money.
    expect(screen.getAllByText(/-\D*1,250\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText(/liability, credit positive/)).toBeInTheDocument();
  });

  it("shows every posting and the zero they come to", async () => {
    show(platform(["OWNER"]).fetchImpl);

    await screen.findByText(/Card payment captured/);
    expect(screen.getByText("platform.clearing.try")).toBeInTheDocument();
    // The zero is the point of a double entry ledger. An explorer that hides it is a bank
    // statement.
    expect(screen.getByText("sums to")).toBeInTheDocument();
    expect(screen.getByText("every entry, always")).toBeInTheDocument();
  });

  it("follows an entry back to the payment that caused it", async () => {
    show(platform(["OWNER"]).fetchImpl);

    const link = await screen.findByRole("link", { name: "the payment" });
    expect(link).toHaveAttribute("href", `/payments/${PAYMENT}`);
  });

  it("says nothing about a payment for an entry that names none", async () => {
    show(
      platform(["OWNER"], {
        ...ENTRY,
        externalReference: "settlement-run-2026-03-01",
      }).fetchImpl,
    );

    await screen.findByText(/Card payment captured/);
    expect(screen.queryByRole("link", { name: "the payment" })).not.toBeInTheDocument();
  });

  it("narrows the entries to one account, and says so in the URL", async () => {
    const { fetchImpl, asked } = platform(["OWNER"]);
    show(fetchImpl);

    await userEvent.click(await screen.findByRole("button", { name: "settlement.try" }));

    await waitFor(() => {
      const last = asked[asked.length - 1] ?? "";
      expect(last).toContain(`accountId=${ACCOUNT}`);
    });
    expect(
      screen.getByRole("heading", { name: /Entries that touched this account/ }),
    ).toBeInTheDocument();
  });

  it("asks for one account's entries straight away when the URL already says so", async () => {
    const { fetchImpl, asked } = platform(["OWNER"]);
    show(fetchImpl, `/books?accountId=${ACCOUNT}`);

    await waitFor(() =>
      expect(asked.some((path) => path.includes(`accountId=${ACCOUNT}`))).toBe(true),
    );
  });

  it("posts nothing, ever", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await screen.findAllByText("settlement.try");

    // Read only, entirely. The books are written by things that moved money, and an entry
    // posted by hand is what double entry exists to make visible.
    expect(screen.queryByRole("button", { name: /post|new entry|add/i })).not.toBeInTheDocument();
  });

  it("is not shown to somebody who may not read the books", async () => {
    show(platform(["ANALYST"]).fetchImpl);

    expect(await screen.findByText("This account may not read the books.")).toBeInTheDocument();
  });
});
