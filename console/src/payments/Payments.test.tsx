import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Payments } from "./Payments";

const MERCHANT = "22222222-2222-4222-8222-222222222222";

function token(): string {
  const payload = btoa(
    JSON.stringify({
      sub: "11111111-1111-4111-8111-111111111111",
      merchant: MERCHANT,
      roles: ["OWNER"],
      exp: Math.floor(Date.now() / 1000) + 900,
    }),
  )
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${payload}.signature`;
}

function payment(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: crypto.randomUUID(),
    amount: 125000,
    currency: "TRY",
    status: "CAPTURED",
    reference: "order-1",
    riskVerdict: "APPROVE",
    riskScore: 10,
    cardLastFour: "4242",
    createdAt: "2026-03-01T09:00:00Z",
    ...over,
  };
}

/** The platform, remembering what it was asked. */
function platform(rows: ReturnType<typeof payment>[], total = rows.length) {
  const asked: string[] = [];
  const fetchImpl = vi.fn(async (path: string) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({ accessToken: token(), tokenType: "Bearer", expiresIn: 900 });
    }
    if (path === "/api/v1/roles") {
      return Response.json({ roles: { OWNER: ["PAYMENT_READ"] }, permissions: [] });
    }
    asked.push(path);
    return new Response(JSON.stringify(rows), {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "X-Total-Count": String(total),
        "X-Page": new URL(path, "http://x").searchParams.get("page") ?? "0",
        "X-Page-Size": new URL(path, "http://x").searchParams.get("size") ?? "25",
      },
    });
  }) as unknown as typeof fetch;

  return { fetchImpl, asked };
}

function show(fetchImpl: typeof fetch, at = "/payments") {
  return render(
    <SessionProvider session={createSession(fetchImpl)}>
      <MemoryRouter initialEntries={[at]}>
        <Payments />
      </MemoryRouter>
    </SessionProvider>,
  );
}

describe("the payments list", () => {
  it("shows amounts as money rather than as minor units", async () => {
    const { fetchImpl } = platform([payment({ amount: 125000, currency: "TRY" })]);
    show(fetchImpl);

    // 125000 is the wire format. Nobody has ever been charged one hundred and twenty five
    // thousand lira for a coffee.
    expect(await screen.findByText(/1,250\.00/)).toBeInTheDocument();
    expect(screen.queryByText("125000")).not.toBeInTheDocument();
  });

  it("asks the platform for the filters that are in the URL", async () => {
    const { fetchImpl, asked } = platform([]);
    show(fetchImpl, "/payments?status=CAPTURED&minAmount=10.00&currency=TRY&page=1");

    await waitFor(() => expect(asked.length).toBeGreaterThan(0));
    const query = new URL(asked[0] ?? "", "http://x").searchParams;

    expect(query.getAll("status")).toEqual(["CAPTURED"]);
    expect(query.get("minAmount")).toBe("1000");
    expect(query.get("page")).toBe("1");
  });

  it("puts a filter somebody chose into the URL, so the search can be sent to somebody", async () => {
    const { fetchImpl, asked } = platform([payment()]);
    show(fetchImpl);
    await screen.findByText(/1,250\.00/);

    await userEvent.click(screen.getByLabelText("held for review"));

    await waitFor(() => {
      const query = new URL(asked[asked.length - 1] ?? "", "http://x").searchParams;
      expect(query.getAll("status")).toEqual(["HELD_FOR_REVIEW"]);
    });
  });

  it("goes back to the first page whenever the question changes", async () => {
    const { fetchImpl, asked } = platform([payment()], 100);
    show(fetchImpl, "/payments?page=3");
    await screen.findByText(/1,250\.00/);

    await userEvent.click(screen.getByLabelText("declined"));

    // Staying on page four of a different search shows an empty table to somebody who has
    // just narrowed to five results, and reads as "nothing found".
    await waitFor(() => {
      const query = new URL(asked[asked.length - 1] ?? "", "http://x").searchParams;
      expect(query.get("page")).toBe("0");
    });
  });

  it("says which filter emptied the table", async () => {
    const { fetchImpl } = platform([]);
    show(fetchImpl, "/payments?status=CAPTURED&reference=order-9");

    // A merchant who filtered themselves into nothing and one who has never taken a payment
    // otherwise see the same blank table, and only one of them can fix it.
    expect(await screen.findByText(/No payments with status captured/)).toBeInTheDocument();
    expect(screen.getByText(/reference order-9/)).toBeInTheDocument();
  });

  it("and says the other thing when nothing was asked", async () => {
    const { fetchImpl } = platform([]);
    show(fetchImpl);

    expect(await screen.findByText("No payments yet.")).toBeInTheDocument();
  });

  it("turns pages, and stops at the ends", async () => {
    const { fetchImpl, asked } = platform([payment()], 60);
    show(fetchImpl);
    await screen.findByText(/1,250\.00/);

    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    await waitFor(() => {
      const query = new URL(asked[asked.length - 1] ?? "", "http://x").searchParams;
      expect(query.get("page")).toBe("1");
    });
  });

  it("says what the platform said when a filter is refused", async () => {
    const refusing = vi.fn(async (path: string) => {
      if (path.startsWith("/api/v1/tokens")) {
        return Response.json({ accessToken: token(), tokenType: "Bearer", expiresIn: 900 });
      }
      if (path === "/api/v1/roles") {
        return Response.json({ roles: {}, permissions: [] });
      }
      return Response.json(
        {
          status: 422,
          code: "UNPROCESSABLE",
          detail: "This platform pages 10,000 payments deep.",
        },
        { status: 422 },
      );
    }) as unknown as typeof fetch;

    show(refusing, "/payments?page=900");

    expect(await screen.findByRole("alert")).toHaveTextContent("10,000 payments deep");
  });
});
