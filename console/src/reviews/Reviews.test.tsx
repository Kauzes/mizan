import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Reviews } from "./Reviews";

const MERCHANT = "22222222-2222-4222-8222-222222222222";
const HELD = "33333333-3333-4333-8333-333333333333";

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

const WAITING = {
  id: HELD,
  amount: 500000,
  currency: "TRY",
  status: "HELD_FOR_REVIEW",
  reference: "order-9",
  description: null,
  acquirerReference: null,
  cardLastFour: null,
  declineReason: null,
  ledgerEntryId: null,
  riskVerdict: "REVIEW",
  riskScore: 55,
  riskReasons: "the amount is about 5 times this merchant's usual of 100000",
  reviewRuling: null,
  reviewRuledBy: null,
  reviewRuledAt: null,
  refundedAmount: 0,
  refundableAmount: 0,
  allowedNext: [],
  createdAt: "2026-03-01T09:00:00Z",
  updatedAt: "2026-03-01T09:00:00Z",
  history: [],
};

interface Sent {
  path: string;
  body: unknown;
}

function platform(options: {
  roles: string[];
  queue?: unknown[];
  learned?: unknown;
  answer?: (sent: Sent) => Response;
}) {
  const sent: Sent[] = [];

  const fetchImpl = vi.fn(async (path: string, init: RequestInit = {}) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({
        accessToken: token(options.roles),
        tokenType: "Bearer",
        expiresIn: 900,
      });
    }
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: { ANALYST: ["PAYMENT_READ", "REVIEW_RULE"], VIEWER: ["PAYMENT_READ"] },
        permissions: ["PAYMENT_READ", "REVIEW_RULE"],
      });
    }
    if (path.endsWith("/reviews")) {
      return Response.json(options.queue ?? [WAITING]);
    }
    if (path.includes("/reviews/rulings")) {
      return Response.json(
        options.learned ?? { rulings: [], learnedAdjustment: 0, known: true },
      );
    }

    const one: Sent = { path, body: JSON.parse(String(init.body ?? "null")) };
    sent.push(one);
    return options.answer ? options.answer(one) : Response.json(WAITING);
  }) as unknown as typeof fetch;

  return { fetchImpl, sent };
}

function show(fetchImpl: typeof fetch) {
  render(
    <SessionProvider session={createSession(fetchImpl)}>
      <MemoryRouter>
        <Reviews />
      </MemoryRouter>
    </SessionProvider>,
  );
}

describe("the review queue", () => {
  it("says why each payment was held, on the row", async () => {
    show(platform({ roles: ["ANALYST"] }).fetchImpl);

    // A decision that needs three clicks of context first is a decision made without it.
    expect(await screen.findByText(/5,000\.00/)).toBeInTheDocument();
    expect(screen.getByText(/5 times this merchant's usual/)).toBeInTheDocument();
    expect(screen.getByText(/review · 55/)).toBeInTheDocument();
  });

  it("will not rule without a reason", async () => {
    show(platform({ roles: ["ANALYST"] }).fetchImpl);
    await screen.findByText(/5,000\.00/);

    // Required at the API too. The console should not be where somebody learns that by
    // being refused.
    expect(screen.getByRole("button", { name: "Release" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Refuse" })).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Why"), "known customer");
    expect(screen.getByRole("button", { name: "Release" })).toBeEnabled();
  });

  it("sends the reason with the ruling", async () => {
    const { fetchImpl, sent } = platform({ roles: ["ANALYST"] });
    show(fetchImpl);
    await screen.findByText(/5,000\.00/);

    await userEvent.type(screen.getByLabelText("Why"), "they called to confirm");
    await userEvent.click(screen.getByRole("button", { name: "Release" }));

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]?.path).toContain(`/reviews/${HELD}/release`);
    expect(sent[0]?.body).toEqual({ why: "they called to confirm" });
  });

  it("says so when somebody else ruled first, rather than showing a status code", async () => {
    const { fetchImpl } = platform({
      roles: ["ANALYST"],
      answer: () =>
        Response.json(
          {
            status: 422,
            code: "UNPROCESSABLE",
            detail: "This payment was already released by grace.",
          },
          { status: 422 },
        ),
    });
    show(fetchImpl);
    await screen.findByText(/5,000\.00/);

    await userEvent.type(screen.getByLabelText("Why"), "looks fine");
    await userEvent.click(screen.getByRole("button", { name: "Release" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("already released by grace");
  });

  it("shows what colleagues decided beside what the platform learned from it", async () => {
    show(
      platform({
        roles: ["ANALYST"],
        learned: {
          rulings: [
            {
              payment_id: "p1",
              ruling: "RELEASED",
              ruled_by: "grace",
              why: "known customer",
            },
          ],
          learnedAdjustment: 5,
          known: true,
        },
      }).fetchImpl,
    );

    await screen.findByRole("heading", { name: "What has been decided" });
    expect(screen.getByText(/by grace — known customer/)).toBeInTheDocument();
    // In words, because "+5" says nothing to somebody working a queue.
    expect(screen.getByText(/raised this merchant's line by 5 points/)).toBeInTheDocument();
    expect(screen.getByText(/at most twenty/)).toBeInTheDocument();
  });

  it("says the line has not moved rather than nothing at all", async () => {
    show(platform({ roles: ["ANALYST"] }).fetchImpl);

    expect(
      await screen.findByText(/takes three rulings the same way before anything moves/),
    ).toBeInTheDocument();
  });

  it("does not invent a drift when the scorer could not be asked", async () => {
    show(
      platform({
        roles: ["ANALYST"],
        learned: { rulings: [], learnedAdjustment: null, known: false },
      }).fetchImpl,
    );

    // Zero is a fact about a merchant whose line has not moved. Saying it when nothing is
    // known would be the console making one up.
    expect(await screen.findByText(/could not be read just now/)).toBeInTheDocument();
    expect(screen.queryByText(/has not moved its line/)).not.toBeInTheDocument();
  });

  it("says the queue is empty rather than showing an empty page", async () => {
    show(platform({ roles: ["ANALYST"], queue: [] }).fetchImpl);

    expect(await screen.findByText(/Nothing is waiting/)).toBeInTheDocument();
  });

  it("is not shown to somebody whose job it is not", async () => {
    show(platform({ roles: ["VIEWER"] }).fetchImpl);

    expect(
      await screen.findByText("This account does not rule on held payments."),
    ).toBeInTheDocument();
  });
});
