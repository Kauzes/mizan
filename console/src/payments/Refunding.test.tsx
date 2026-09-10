import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Refunding } from "./Refunding";
import type { Payment } from "./types";

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

const CAPTURED: Payment = {
  id: "33333333-3333-4333-8333-333333333333",
  amount: 125000,
  currency: "TRY",
  status: "CAPTURED",
  reference: "order-1",
  description: null,
  acquirerReference: "auth_1",
  cardLastFour: "4242",
  declineReason: null,
  ledgerEntryId: null,
  riskVerdict: "APPROVE",
  riskScore: 5,
  riskReasons: null,
  reviewRuling: null,
  reviewRuledBy: null,
  reviewRuledAt: null,
  refundedAmount: 25000,
  refundableAmount: 100000,
  allowedNext: [],
  createdAt: "2026-03-01T09:00:00Z",
  updatedAt: "2026-03-01T09:00:00Z",
  history: [],
};

interface Sent {
  path: string;
  key: string | null;
  body: unknown;
}

function platform(roles: string[], answer?: (sent: Sent) => Response) {
  const sent: Sent[] = [];

  const fetchImpl = vi.fn(async (path: string, init: RequestInit = {}) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({ accessToken: token(roles), tokenType: "Bearer", expiresIn: 900 });
    }
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: { OWNER: ["PAYMENT_READ", "PAYMENT_WRITE"], VIEWER: ["PAYMENT_READ"] },
        permissions: ["PAYMENT_READ", "PAYMENT_WRITE"],
      });
    }

    const headers = new Headers(init.headers);
    const one: Sent = {
      path,
      key: headers.get("Idempotency-Key"),
      body: JSON.parse(String(init.body ?? "null")),
    };
    sent.push(one);

    return answer ? answer(one) : Response.json({ id: "r1" }, { status: 201 });
  }) as unknown as typeof fetch;

  return { fetchImpl, sent };
}

function show(fetchImpl: typeof fetch, onRefunded = vi.fn()) {
  render(
    <SessionProvider session={createSession(fetchImpl)}>
      <Refunding payment={CAPTURED} refunds={[]} onRefunded={onRefunded} />
    </SessionProvider>,
  );
  return onRefunded;
}

async function open() {
  await userEvent.click(await screen.findByRole("button", { name: "Refund" }));
}

describe("giving money back", () => {
  it("says what is left before anybody types anything", async () => {
    show(platform(["OWNER"]).fetchImpl);

    // The limit as a fact rather than as a refusal after the fact.
    expect(await screen.findByText(/1,000\.00.*can still be given back/)).toBeInTheDocument();
    await open();
    expect(screen.getByText(/1,250\.00 was taken/)).toBeInTheDocument();
    expect(screen.getByText(/250\.00 given back/)).toBeInTheDocument();
  });

  it("will not let somebody ask for more than remains", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await open();

    await userEvent.type(screen.getByLabelText("How much, in TRY"), "1200");

    // The server refuses this too. Both are needed, for the reason every invariant on this
    // platform is written twice.
    expect(await screen.findByRole("alert")).toHaveTextContent(/Only \D*1,000\.00 is left/);
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
  });

  it("refuses nothing, and refuses what is not an amount", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await open();
    const amount = screen.getByLabelText("How much, in TRY");

    await userEvent.type(amount, "0");
    expect(await screen.findByRole("alert")).toHaveTextContent("more than nothing");

    await userEvent.clear(amount);
    await userEvent.type(amount, "half");
    expect(await screen.findByRole("alert")).toHaveTextContent("not an amount in TRY");
  });

  it("confirms with the amount, the currency and what will be left", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await open();

    await userEvent.type(screen.getByLabelText("How much, in TRY"), "400");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    // A dialog that says "are you sure" and nothing else is a dialog people click through.
    const confirmation = await screen.findByRole("group", { name: "Confirm this refund" });
    expect(confirmation).toHaveTextContent(/Give \D*400\.00 back/);
    expect(confirmation).toHaveTextContent(/leaves \D*600\.00 of \D*1,250\.00 refundable/);
  });

  it("sends minor units, and the same key however many times it is clicked", async () => {
    const { fetchImpl, sent } = platform(["OWNER"]);
    show(fetchImpl);
    await open();

    await userEvent.type(screen.getByLabelText("How much, in TRY"), "12.34");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    const give = await screen.findByRole("button", { name: /Refund \D*12\.34/ });
    await userEvent.click(give);

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]?.body).toMatchObject({ amount: 1234, currency: "TRY" });
    expect(sent[0]?.key).toBeTruthy();
    // The reference names the payment it belongs to, and is unique so that a second,
    // deliberate refund is not answered with the first one.
    expect((sent[0]?.body as { reference: string }).reference).toContain("order-1-refund-");
  });

  it("chooses a new intent when somebody goes back to change the amount", async () => {
    const { fetchImpl, sent } = platform(["OWNER"]);
    show(fetchImpl);
    await open();

    await userEvent.type(screen.getByLabelText("How much, in TRY"), "100");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Back" }));

    const amount = screen.getByLabelText("How much, in TRY");
    await userEvent.clear(amount);
    await userEvent.type(amount, "200");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(await screen.findByRole("button", { name: /Refund \D*200\.00/ }));

    await waitFor(() => expect(sent.length).toBe(1));
    // Different amount, different intent. Reusing the key would have answered with the
    // hundred lira refund that was never made.
    expect(sent[0]?.body).toMatchObject({ amount: 20000 });
  });

  it("says what the platform said, chosen by the code rather than the status", async () => {
    const { fetchImpl } = platform(["OWNER"], () =>
      Response.json(
        {
          status: 504,
          code: "UPSTREAM_TIMEOUT",
          detail: "The service did not respond in time.",
        },
        { status: 504 },
      ),
    );
    show(fetchImpl);
    await open();

    await userEvent.type(screen.getByLabelText("How much, in TRY"), "100");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(await screen.findByRole("button", { name: /Refund/ }));

    // "Whether it worked is unknown" is the only honest thing to say, and the only thing
    // that stops somebody refunding twice.
    expect(await screen.findByRole("alert")).toHaveTextContent("not yet known");
  });

  it("tells the page to read itself again once the money is back", async () => {
    const { fetchImpl } = platform(["OWNER"]);
    const onRefunded = show(fetchImpl);
    await open();

    await userEvent.type(screen.getByLabelText("How much, in TRY"), "100");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(await screen.findByRole("button", { name: /Refund/ }));

    // Read again rather than patched here: what the platform says is the answer.
    await waitFor(() => expect(onRefunded).toHaveBeenCalled());
  });

  it("is not offered to somebody who may not do it", async () => {
    render(
      <SessionProvider session={createSession(platform(["VIEWER"]).fetchImpl)}>
        <Refunding payment={CAPTURED} refunds={[]} onRefunded={vi.fn()} />
      </SessionProvider>,
    );

    await new Promise((settle) => setTimeout(settle, 20));
    expect(screen.queryByRole("button", { name: "Refund" })).not.toBeInTheDocument();
  });

  it("is not offered on a payment with nothing left to give back", async () => {
    render(
      <SessionProvider session={createSession(platform(["OWNER"]).fetchImpl)}>
        <Refunding
          payment={{ ...CAPTURED, refundedAmount: 125000, refundableAmount: 0 }}
          refunds={[]}
          onRefunded={vi.fn()}
        />
      </SessionProvider>,
    );

    await new Promise((settle) => setTimeout(settle, 20));
    expect(screen.queryByRole("button", { name: "Refund" })).not.toBeInTheDocument();
  });
});
