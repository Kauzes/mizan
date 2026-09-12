import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { createSession } from "./session/session";
import { SessionProvider } from "./session/SessionProvider";

function tokenFor(roles: string[]): string {
  const payload = btoa(
    JSON.stringify({
      sub: "11111111-1111-4111-8111-111111111111",
      merchant: "22222222-2222-4222-8222-222222222222",
      roles,
      exp: Math.floor(Date.now() / 1000) + 900,
    }),
  )
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${payload}.signature`;
}

/** The platform, as far as this console is concerned. */
function platform(options: { session: boolean; roles?: string[] }) {
  return vi.fn(async (path: string) => {
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: {
          OWNER: ["PAYMENT_READ", "PAYMENT_WRITE", "ENTRY_READ", "REVIEW_RULE", "USER_MANAGE"],
          ANALYST: ["PAYMENT_READ", "ENTRY_READ", "REVIEW_RULE"],
        },
        permissions: ["PAYMENT_READ", "PAYMENT_WRITE", "ENTRY_READ", "REVIEW_RULE"],
      });
    }
    if (path === "/api/v1/tokens/refresh" && !options.session) {
      return Response.json(
        { status: 401, detail: "The credentials are not valid.", code: "UNAUTHORIZED" },
        { status: 401 },
      );
    }
    if (path === "/api/v1/tokens" || path === "/api/v1/tokens/refresh") {
      return Response.json({
        accessToken: tokenFor(options.roles ?? ["OWNER"]),
        tokenType: "Bearer",
        expiresIn: 900,
        refreshToken: "not-this-console's-business",
        refreshExpiresIn: 2592000,
      });
    }
    if (path.includes("/summary")) {
      return Response.json({
        from: "2026-03-01T00:00:00Z",
        to: "2026-03-02T00:00:00Z",
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
      });
    }
    if (path.includes("/payments")) {
      return new Response("[]", {
        status: 200,
        headers: { "Content-Type": "application/json", "X-Total-Count": "0" },
      });
    }
    return new Response(null, { status: 204 });
  }) as unknown as typeof fetch;
}

function show(fetchImpl: typeof fetch) {
  return render(
    <SessionProvider session={createSession(fetchImpl)}>
      <App />
    </SessionProvider>,
  );
}

describe("opening the console", () => {
  it("asks for credentials when the browser is carrying nothing", async () => {
    show(platform({ session: false }));

    expect(await screen.findByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });

  it("lands somebody straight on the overview when the browser still has a session", async () => {
    show(platform({ session: true }));

    // The reason the console has a third state on its first paint: without one, somebody who
    // is already signed in gets a login screen flashed at them on every reload.
    expect(await screen.findByRole("heading", { name: "How business is" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("says what the platform said when the credentials are refused", async () => {
    const refusing = vi.fn(async () =>
      Response.json(
        { status: 401, detail: "The credentials are not valid.", code: "UNAUTHORIZED" },
        { status: 401 },
      ),
    ) as unknown as typeof fetch;

    show(refusing);
    await screen.findByRole("button", { name: "Sign in" });

    await userEvent.type(screen.getByLabelText("Email"), "owner@mizan.local");
    await userEvent.type(screen.getByLabelText("Password"), "the-wrong-one");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    // Word for word what identity says, because identity deliberately answers a wrong
    // password and an unknown address identically, and a console that elaborated would
    // undo that.
    expect(await screen.findByRole("alert")).toHaveTextContent("The credentials are not valid.");
  });

  it("signs in and arrives somewhere useful", async () => {
    show(platform({ session: false }));
    await screen.findByRole("button", { name: "Sign in" });

    await userEvent.type(screen.getByLabelText("Email"), "owner@mizan.local");
    await userEvent.type(screen.getByLabelText("Password"), "a-long-enough-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "How business is" })).toBeInTheDocument();
  });

  it("puts somebody back at the door when they sign out", async () => {
    show(platform({ session: true }));
    await screen.findByRole("heading", { name: "How business is" });

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });
});
