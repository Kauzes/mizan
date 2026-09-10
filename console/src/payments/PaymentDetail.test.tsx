import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { PaymentDetail } from "./PaymentDetail";

const MERCHANT = "22222222-2222-4222-8222-222222222222";
const PAYMENT = "33333333-3333-4333-8333-333333333333";
const ENTRY = "44444444-4444-4444-8444-444444444444";

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

const CAPTURED = {
  id: PAYMENT,
  amount: 125000,
  currency: "TRY",
  status: "CAPTURED",
  reference: "order-1",
  description: null,
  acquirerReference: "auth_123",
  cardLastFour: "4242",
  declineReason: null,
  ledgerEntryId: ENTRY,
  riskVerdict: "REVIEW",
  riskScore: 55,
  riskReasons: "the amount is unusual for this merchant",
  reviewRuling: "RELEASED",
  reviewRuledBy: "11111111-1111-4111-8111-111111111111",
  reviewRuledAt: "2026-03-01T10:00:00Z",
  refundedAmount: 0,
  refundableAmount: 125000,
  allowedNext: [],
  createdAt: "2026-03-01T09:00:00Z",
  updatedAt: "2026-03-01T10:00:00Z",
  history: [
    { from: null, to: "CREATED", because: null, at: "2026-03-01T09:00:00Z" },
    {
      from: "CREATED",
      to: "HELD_FOR_REVIEW",
      because: "the amount is unusual for this merchant",
      at: "2026-03-01T09:30:00Z",
    },
    { from: "HELD_FOR_REVIEW", to: "CAPTURED", because: null, at: "2026-03-01T10:00:00Z" },
  ],
};

function platform(roles: string[], overrides: Record<string, unknown> = {}) {
  const asked: string[] = [];

  const fetchImpl = vi.fn(async (path: string) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({ accessToken: token(roles), tokenType: "Bearer", expiresIn: 900 });
    }
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: {
          OWNER: ["PAYMENT_READ", "ENTRY_READ", "WEBHOOK_READ"],
          VIEWER: ["PAYMENT_READ"],
        },
        permissions: ["PAYMENT_READ", "ENTRY_READ", "WEBHOOK_READ"],
      });
    }

    asked.push(path);

    if (path.endsWith(`/payments/${PAYMENT}`)) {
      return Response.json({ ...CAPTURED, ...overrides });
    }
    if (path.endsWith("/refunds")) {
      return Response.json([]);
    }
    if (path.includes("/entries/")) {
      return Response.json({
        id: ENTRY,
        externalReference: `payment-${PAYMENT}`,
        description: "Payment captured",
        occurredAt: "2026-03-01T10:00:00Z",
        postings: [
          { accountCode: "cash.try", amount: 125000, currency: "TRY", direction: "DEBIT" },
          {
            accountCode: "settlement.try",
            amount: -125000,
            currency: "TRY",
            direction: "CREDIT",
          },
        ],
      });
    }
    if (path.includes("/webhook-deliveries")) {
      return Response.json([
        {
          id: "55555555-5555-4555-8555-555555555555",
          endpoint_id: "66666666-6666-4666-8666-666666666666",
          payment_id: PAYMENT,
          event_type: "payment.captured",
          status: "DELIVERED",
          attempts: 2,
          last_status_code: 200,
          last_error: null,
          delivered_at: "2026-03-01T10:00:05Z",
          created_at: "2026-03-01T10:00:01Z",
        },
      ]);
    }
    return new Response(null, { status: 204 });
  }) as unknown as typeof fetch;

  return { fetchImpl, asked };
}

function show(fetchImpl: typeof fetch) {
  return render(
    <SessionProvider session={createSession(fetchImpl)}>
      <MemoryRouter initialEntries={[`/payments/${PAYMENT}`]}>
        <Routes>
          <Route path="/payments/:paymentId" element={<PaymentDetail />} />
        </Routes>
      </MemoryRouter>
    </SessionProvider>,
  );
}

describe("one payment", () => {
  it("shows the whole timeline in the words the platform kept", async () => {
    show(platform(["OWNER"]).fetchImpl);

    await screen.findByRole("heading", { name: /order-1/ });
    const timeline = screen.getByRole("list", { name: "" }) ?? undefined;
    expect(timeline).toBeDefined();

    expect(screen.getByText("created")).toBeInTheDocument();
    expect(screen.getAllByText(/held for review/).length).toBeGreaterThan(0);
    // The reason the scorer gave, kept beside the transition rather than looked up later.
    expect(
      screen.getAllByText(/the amount is unusual for this merchant/).length,
    ).toBeGreaterThan(0);
  });

  it("says what risk decided and who overruled it", async () => {
    show(platform(["OWNER"]).fetchImpl);

    await screen.findByRole("heading", { name: /order-1/ });
    expect(screen.getByText("review")).toBeInTheDocument();
    expect(screen.getByText(/released by/i)).toBeInTheDocument();
  });

  it("treats an unscored payment as unscored rather than as an empty field", async () => {
    // UNAVAILABLE is a verdict. A merchant reviewing a day of these is asking a different
    // question from one looking at a payment that was never scored at all.
    show(
      platform(["OWNER"], {
        riskVerdict: "UNAVAILABLE",
        riskScore: null,
        riskReasons: "risk could not be asked",
        reviewRuling: null,
      }).fetchImpl,
    );

    expect(
      await screen.findByText("The scorer could not be asked, so this payment went through unscored."),
    ).toBeInTheDocument();
  });

  it("shows the entry it produced, with the postings summing to zero", async () => {
    show(platform(["OWNER"]).fetchImpl);

    await screen.findByRole("heading", { name: "In the books" });
    expect(await screen.findByText("cash.try")).toBeInTheDocument();
    // The zero is the whole point of a double entry ledger, so it is shown rather than left
    // to be worked out.
    expect(screen.getByText("sums to")).toBeInTheDocument();
    expect(screen.getByText("every entry, always")).toBeInTheDocument();
  });

  it("shows what the merchant's endpoints were told, including how hard it was", async () => {
    show(platform(["OWNER"]).fetchImpl);

    await screen.findByRole("heading", { name: "What your endpoints were told" });
    expect(await screen.findByText("payment.captured")).toBeInTheDocument();
    expect(screen.getByText(/after 2 attempts/)).toBeInTheDocument();
  });

  it("does not ask for what this role may not read", async () => {
    const { fetchImpl, asked } = platform(["VIEWER"]);
    show(fetchImpl);

    await screen.findByRole("heading", { name: /order-1/ });
    await waitFor(() => expect(asked.some((path) => path.endsWith("/refunds"))).toBe(true));

    // A panel that renders a refusal is a panel that told somebody a thing exists.
    expect(asked.some((path) => path.includes("/entries/"))).toBe(false);
    expect(asked.some((path) => path.includes("/webhook-deliveries"))).toBe(false);
    expect(screen.queryByRole("heading", { name: "In the books" })).not.toBeInTheDocument();
  });

  it("says what the platform said when the payment is not there", async () => {
    const missing = vi.fn(async (path: string) => {
      if (path.startsWith("/api/v1/tokens")) {
        return Response.json({ accessToken: token(["OWNER"]), tokenType: "Bearer", expiresIn: 900 });
      }
      if (path === "/api/v1/roles") {
        return Response.json({ roles: {}, permissions: [] });
      }
      return Response.json(
        { status: 404, code: "NOT_FOUND", detail: "No payment with that id." },
        { status: 404 },
      );
    }) as unknown as typeof fetch;

    show(missing);

    expect(await screen.findByRole("alert")).toHaveTextContent("No payment with that id.");
  });
});
