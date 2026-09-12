import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { createSession } from "../session/session";
import { SessionProvider } from "../session/SessionProvider";
import { Settings } from "./Settings";

const MERCHANT = "22222222-2222-4222-8222-222222222222";
const KEY = "77777777-7777-4777-8777-777777777777";
const ENDPOINT = "88888888-8888-4888-8888-888888888888";

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

interface Sent {
  path: string;
  method: string;
  body: unknown;
}

function platform(roles: string[], options: { keys?: unknown[]; endpoints?: unknown[] } = {}) {
  const sent: Sent[] = [];

  const fetchImpl = vi.fn(async (path: string, init: RequestInit = {}) => {
    if (path.startsWith("/api/v1/tokens")) {
      return Response.json({ accessToken: token(roles), tokenType: "Bearer", expiresIn: 900 });
    }
    if (path === "/api/v1/roles") {
      return Response.json({
        roles: {
          OWNER: ["API_KEY_MANAGE", "WEBHOOK_READ", "WEBHOOK_MANAGE"],
          VIEWER: ["WEBHOOK_READ"],
          ANALYST: [],
        },
        permissions: ["API_KEY_MANAGE", "WEBHOOK_READ", "WEBHOOK_MANAGE"],
      });
    }

    const method = init.method ?? "GET";
    sent.push({ path, method, body: JSON.parse(String(init.body ?? "null")) });

    if (path.endsWith("/api-keys") && method === "GET") {
      return Response.json(
        options.keys ?? [
          {
            id: KEY,
            keyId: "mzk_live_1",
            name: "nightly reconciliation",
            role: "VIEWER",
            createdAt: "2026-03-01T09:00:00Z",
            lastUsedAt: null,
            revokedAt: null,
            rotatedFrom: null,
          },
        ],
      );
    }
    if (path.endsWith("/api-keys") && method === "POST") {
      return Response.json(
        {
          key: {
            id: "new",
            keyId: "mzk_live_2",
            name: "a new one",
            role: "VIEWER",
            createdAt: "2026-03-02T09:00:00Z",
            lastUsedAt: null,
            revokedAt: null,
            rotatedFrom: null,
          },
          secret: "mzs_the_only_time_this_is_shown",
        },
        { status: 201 },
      );
    }
    if (path.includes("/api-keys/") && path.endsWith("/rotate")) {
      return Response.json({
        key: { id: KEY, keyId: "mzk_live_1", name: "n", role: "VIEWER", createdAt: "x",
          lastUsedAt: null, revokedAt: null, rotatedFrom: null },
        secret: "mzs_a_replacement",
      });
    }
    if (path.endsWith("/webhook-endpoints") && method === "GET") {
      return Response.json(
        options.endpoints ?? [
          {
            id: ENDPOINT,
            url: "https://api.example.com/hooks",
            description: "Production order service",
            eventTypes: ["payment.captured"],
            enabled: true,
            secretRotatedAt: null,
            createdAt: "2026-03-01T09:00:00Z",
          },
        ],
      );
    }
    if (path.endsWith("/deliveries?limit=25")) {
      return Response.json([
        {
          id: "d1",
          endpoint_id: ENDPOINT,
          payment_id: "p1",
          event_type: "payment.captured",
          status: "FAILED",
          attempts: 4,
          last_status_code: 500,
          last_error: "the server said 500",
          delivered_at: null,
          created_at: "2026-03-01T10:00:00Z",
        },
      ]);
    }
    return new Response(null, { status: 204 });
  }) as unknown as typeof fetch;

  return { fetchImpl, sent };
}

function show(fetchImpl: typeof fetch) {
  render(
    <SessionProvider session={createSession(fetchImpl)}>
      <Settings />
    </SessionProvider>,
  );
}

describe("keys", () => {
  it("warns that the secret is shown once before there is one", async () => {
    show(platform(["OWNER"]).fetchImpl);

    // Said before the key exists rather than after the secret has scrolled away.
    expect(
      await screen.findByText("The secret is shown once, when it is issued."),
    ).toBeInTheDocument();
  });

  it("shows the secret once, and only on the way out", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await screen.findByText("nightly reconciliation");

    await userEvent.type(screen.getByLabelText("What is it for"), "a new one");
    await userEvent.click(screen.getByRole("button", { name: "Issue a key" }));

    const secret = await screen.findByText("mzs_the_only_time_this_is_shown");
    expect(secret).toBeInTheDocument();
    expect(screen.getByText(/only time it is shown/)).toBeInTheDocument();

    // Nothing secret is written anywhere the browser remembers.
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(window.location.search).not.toContain("mzs_");
  });

  it("takes a deliberate confirmation to hide a secret", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await screen.findByText("nightly reconciliation");

    await userEvent.type(screen.getByLabelText("What is it for"), "a new one");
    await userEvent.click(screen.getByRole("button", { name: "Issue a key" }));
    await screen.findByText("mzs_the_only_time_this_is_shown");

    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    // Not gone yet. A click on whatever was nearest should not lose something unrecoverable.
    expect(screen.getByText("mzs_the_only_time_this_is_shown")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Yes, I have kept it" }));
    await waitFor(() =>
      expect(screen.queryByText("mzs_the_only_time_this_is_shown")).not.toBeInTheDocument(),
    );
  });

  it("names what will stop working before revoking anything", async () => {
    const { fetchImpl, sent } = platform(["OWNER"]);
    show(fetchImpl);
    await screen.findByText("nightly reconciliation");

    await userEvent.click(screen.getByRole("button", { name: "Revoke" }));

    // "Are you sure" does not tell somebody which production integration they are breaking.
    const confirmation = await screen.findByRole("group", {
      name: "Confirm revoking this key",
    });
    expect(confirmation).toHaveTextContent("mzk_live_1");
    expect(confirmation).toHaveTextContent("nightly reconciliation");
    expect(confirmation).toHaveTextContent(/stops every request signed with it/);

    // And nothing was sent while it was only being considered.
    expect(sent.some((one) => one.method === "DELETE")).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "Revoke it" }));
    await waitFor(() =>
      expect(sent.some((one) => one.method === "DELETE" && one.path.includes(KEY))).toBe(true),
    );
  });

  it("shows a replacement secret when a key is rotated", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await screen.findByText("nightly reconciliation");

    await userEvent.click(screen.getByRole("button", { name: "Rotate" }));

    expect(await screen.findByText("mzs_a_replacement")).toBeInTheDocument();
  });
});

describe("endpoints", () => {
  it("shows every attempt, not a count of failures", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await screen.findByText("https://api.example.com/hooks");

    await userEvent.click(screen.getByRole("button", { name: "Deliveries" }));

    // A merchant whose endpoint was down for an hour needs to see the hour.
    expect((await screen.findAllByText("payment.captured")).length).toBeGreaterThan(1);
    expect(screen.getByText(/after 4 attempts/)).toBeInTheDocument();
    expect(screen.getByText(/the server said 500/)).toBeInTheDocument();
  });

  it("offers to send a failed delivery again", async () => {
    const { fetchImpl, sent } = platform(["OWNER"]);
    show(fetchImpl);
    await screen.findByText("https://api.example.com/hooks");

    await userEvent.click(screen.getByRole("button", { name: "Deliveries" }));
    await userEvent.click(await screen.findByRole("button", { name: "send again" }));

    await waitFor(() =>
      expect(sent.some((one) => one.path.endsWith("/redeliver"))).toBe(true),
    );
  });

  it("says what removing costs, and that disabling does not", async () => {
    show(platform(["OWNER"]).fetchImpl);
    await screen.findByText("https://api.example.com/hooks");

    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    const confirmation = await screen.findByRole("group", {
      name: "Confirm removing this endpoint",
    });
    expect(confirmation).toHaveTextContent(/takes its history with it/);
    expect(confirmation).toHaveTextContent(/Disabling keeps both/);
  });

  it("lets a reader look without offering them anything to change", async () => {
    show(platform(["VIEWER"]).fetchImpl);

    await screen.findByText("https://api.example.com/hooks");
    expect(screen.queryByRole("button", { name: "Remove" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rotate secret" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Where to send")).not.toBeInTheDocument();
    // Keys are a different permission, and a viewer has neither.
    expect(screen.queryByRole("heading", { name: "API keys" })).not.toBeInTheDocument();
  });

  it("says so to somebody who manages neither", async () => {
    show(platform(["ANALYST"]).fetchImpl);

    expect(
      await screen.findByText(/This account manages neither keys nor endpoints/),
    ).toBeInTheDocument();
  });
});
