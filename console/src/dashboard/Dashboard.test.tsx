import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Dashboard } from "./Dashboard";

const MERCHANT = "22222222-2222-4222-8222-222222222222";

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

const BUSY = {
  from: "2026-02-01T00:00:00Z",
  to: "2026-03-03T00:00:00Z",
  totals: {
    created: 14,
    attempted: 12,
    authorized: 9,
    captured: 8,
    declinedByAcquirer: 2,
    refusedByPlatform: 1,
    held: 1,
    unknown: 1,
  },
  authorizationRate: 0.75,
  volume: [
    { currency: "TRY", captured: 1_000_00, refunded: 25_000, payments: 8 },
    { currency: "JPY", captured: 1250, refunded: 0, payments: 1 },
  ],
  byDay: [
    { day: "2026-03-01", attempted: 8, authorized: 6, captured: 6, declined: 2, held: 0 },
    { day: "2026-03-02", attempted: 4, authorized: 3, captured: 2, declined: 1, held: 1 },
  ],
  refusals: [
    { by: "ACQUIRER", reason: "insufficient_funds", payments: 2 },
    { by: "PLATFORM", reason: "This payment was refused: the amount is unusual", payments: 1 },
  ],
  needsSomebody: { waitingForAPerson: 1, needingAnOperator: 1 },
};

const QUIET = {
  ...BUSY,
  totals: {
    created: 0,
    attempted: 0,
    authorized: 0,
    captured: 0,
    declinedByAcquirer: 0,
    refusedByPlatform: 0,
    held: 0,
    unknown: 0,
  },
  authorizationRate: null,
  volume: [],
  byDay: [],
  refusals: [],
  needsSomebody: { waitingForAPerson: 0, needingAnOperator: 0 },
};

function platform(summary: unknown, roles = ["OWNER"]) {
  const asked: string[] = [];

  const fetchImpl = vi.fn(async (path: string) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({ accessToken: token(roles), tokenType: "Bearer", expiresIn: 900 });
    }
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: { OWNER: ["PAYMENT_READ", "REVIEW_RULE"], VIEWER: ["PAYMENT_READ"] },
        permissions: ["PAYMENT_READ", "REVIEW_RULE"],
      });
    }
    asked.push(path);
    return Response.json(summary);
  }) as unknown as typeof fetch;

  return { fetchImpl, asked };
}

function show(fetchImpl: typeof fetch, at = "/") {
  render(
    <SessionProvider session={createSession(fetchImpl)}>
      <MemoryRouter initialEntries={[at]}>
        <Dashboard />
      </MemoryRouter>
    </SessionProvider>,
  );
}

describe("how business is", () => {
  it("puts the volume beside the rate", async () => {
    show(platform(BUSY).fetchImpl);

    // A rate without a denominator is a rumour. 75% of twelve and 75% of twelve thousand are
    // different facts.
    expect(await screen.findByText("75%")).toBeInTheDocument();
    expect(screen.getByText("9 of 12 attempted")).toBeInTheDocument();
  });

  it("says nothing was attempted rather than showing nought per cent", async () => {
    show(platform(QUIET).fetchImpl);

    // Zero per cent says every payment failed. No payments says something else entirely.
    expect(
      await screen.findByText(/No payments in this period/),
    ).toBeInTheDocument();
    expect(screen.queryByText("0%")).not.toBeInTheDocument();
  });

  it("splits refusals by who refused them", async () => {
    show(platform(BUSY).fetchImpl);

    await screen.findByRole("heading", { name: "By the acquirer" });
    expect(screen.getByText("insufficient_funds")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "By this platform" })).toBeInTheDocument();
    expect(screen.getByText(/the amount is unusual/)).toBeInTheDocument();
    // Neither of those, and worth saying so.
    expect(screen.getByText(/never got an answer about/)).toBeInTheDocument();
  });

  it("never adds one currency to another", async () => {
    show(platform(BUSY).fetchImpl);

    await screen.findByRole("heading", { name: "Money" });
    expect(screen.getByText(/1,000\.00/)).toBeInTheDocument();
    expect(screen.getByText(/1,250/)).toBeInTheDocument();
    // And says what was given back, in the currency it was given back in.
    expect(screen.getByText(/250\.00 given back/)).toBeInTheDocument();
  });

  it("leads with what is waiting for somebody", async () => {
    show(platform(BUSY).fetchImpl);

    const waiting = await screen.findByRole("status");
    expect(waiting).toHaveTextContent(/held, waiting for a person/);
    expect(waiting).toHaveTextContent(/could not find out what happened/);
    // And a way to go and deal with it, rather than a number to worry about.
    expect(screen.getByRole("link", { name: "The review queue" })).toHaveAttribute(
      "href",
      "/reviews",
    );
  });

  it("does not offer the queue to somebody who cannot work it", async () => {
    show(platform(BUSY, ["VIEWER"]).fetchImpl);

    await screen.findByRole("status");
    expect(screen.queryByRole("link", { name: "The review queue" })).not.toBeInTheDocument();
    expect(screen.getByText(/An analyst can release them/)).toBeInTheDocument();
  });

  it("draws two charts rather than one with two scales", async () => {
    show(platform(BUSY).fetchImpl);

    // Putting a rate and a volume on one plot means choosing where two axes line up, and that
    // choice invents a correlation the data does not contain.
    const charts = await screen.findAllByRole("img");
    expect(charts).toHaveLength(2);
    expect(charts[0]).toHaveAccessibleName(/Authorization rate for each of 2 days/);
    expect(charts[1]).toHaveAccessibleName(/Payments attempted on each of 2 days/);
  });

  it("offers the same figures as a table", async () => {
    show(platform(BUSY).fetchImpl);

    await userEvent.click(await screen.findByRole("button", { name: "Show the figures" }));

    // Not a fallback. Somebody who cannot hover is not a lesser reader of this page.
    const table = screen.getByRole("table");
    expect(table).toHaveTextContent("2026-03-01");
    expect(table).toHaveTextContent("2026-03-02");
  });

  it("asks about the range in the URL, in the merchant's own days", async () => {
    const { fetchImpl, asked } = platform(BUSY);
    show(fetchImpl, "/?days=7");

    await screen.findByText("75%");
    const query = new URL(asked[0] ?? "", "http://x").searchParams;
    expect(query.get("zone")).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);

    const from = new Date(query.get("from") ?? "");
    const to = new Date(query.get("to") ?? "");
    expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(7);
  });

  it("changes the range, and says so in the URL", async () => {
    const { fetchImpl, asked } = platform(BUSY);
    show(fetchImpl);
    await screen.findByText("75%");

    await userEvent.click(screen.getByRole("button", { name: "90 days" }));

    const query = new URL(asked[asked.length - 1] ?? "", "http://x").searchParams;
    const from = new Date(query.get("from") ?? "");
    const to = new Date(query.get("to") ?? "");
    expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(90);
  });
});
