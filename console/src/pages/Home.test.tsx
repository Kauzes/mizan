import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Home } from "./Home";

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

function signedInAs(roles: string[]) {
  const fetchImpl = vi.fn(async (path: string) => {
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: {
          OWNER: ["PAYMENT_READ", "PAYMENT_WRITE", "ENTRY_READ", "REVIEW_RULE", "USER_MANAGE"],
          ANALYST: ["PAYMENT_READ", "ENTRY_READ", "REVIEW_RULE"],
        },
        permissions: ["PAYMENT_READ", "PAYMENT_WRITE", "ENTRY_READ", "REVIEW_RULE"],
      });
    }
    return Response.json({
      accessToken: tokenFor(roles),
      tokenType: "Bearer",
      expiresIn: 900,
    });
  }) as unknown as typeof fetch;

  return render(
    <SessionProvider session={createSession(fetchImpl)}>
      <Home />
    </SessionProvider>,
  );
}

describe("what this account may do", () => {
  it("comes from the platform's own table rather than a copy kept here", async () => {
    signedInAs(["ANALYST"]);

    await waitFor(() =>
      expect(screen.getByText("Rule on payments the platform held").closest("li")).toHaveClass(
        "yes",
      ),
    );
    // An analyst deciding whether a payment is fraud has no reason to be able to add a user,
    // and the point of a separate role is that they cannot.
    expect(screen.getByText("Take and refund payments").closest("li")).toHaveClass("no");
    expect(screen.getByText("Add people and change what they may do").closest("li")).toHaveClass(
      "no",
    );
  });

  it("shows an owner everything, because that is what the role means", async () => {
    signedInAs(["OWNER"]);

    await waitFor(() =>
      expect(screen.getByText("Take and refund payments").closest("li")).toHaveClass("yes"),
    );
    expect(screen.getByText("Add people and change what they may do").closest("li")).toHaveClass(
      "yes",
    );
  });
});
