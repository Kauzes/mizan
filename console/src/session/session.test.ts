import { beforeEach, describe, expect, it, vi } from "vitest";
import { PlatformError } from "../api/problems";
import { createSession } from "./session";

/** A token that says who it is, so the session has something to read. */
function accessToken(expiresIn = 900): string {
  const payload = btoa(
    JSON.stringify({
      sub: "11111111-1111-4111-8111-111111111111",
      merchant: "22222222-2222-4222-8222-222222222222",
      roles: ["OWNER"],
      exp: Math.floor(Date.now() / 1000) + expiresIn,
    }),
  )
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${payload}.signature`;
}

function issued(): Response {
  return new Response(
    JSON.stringify({
      accessToken: accessToken(),
      tokenType: "Bearer",
      expiresIn: 900,
      // Still in the body, as it is for every other client. This console must not keep it.
      refreshToken: "a-refresh-token-nobody-here-should-remember",
      refreshExpiresIn: 2592000,
    }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
}

function refused(status: number, code: string): Response {
  return new Response(
    JSON.stringify({
      type: `https://mizan.kauzes.dev/errors/${code.toLowerCase()}`,
      title: code.toLowerCase(),
      status,
      detail: "No.",
      code,
      correlationId: "abc-123",
    }),
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

describe("signing in", () => {
  let calls: Array<{ path: string; init: RequestInit }>;

  beforeEach(() => {
    calls = [];
  });

  function recording(answer: (path: string, init: RequestInit) => Response) {
    return vi.fn(async (path: string, init: RequestInit = {}) => {
      calls.push({ path, init });
      return answer(path, init);
    }) as unknown as typeof fetch;
  }

  it("holds the access token and reads who it belongs to", async () => {
    const session = createSession(recording(() => issued()));

    await session.signIn("owner@mizan.local", "a-long-enough-password");

    expect(session.state().status).toBe("signed-in");
    expect(session.state().caller?.merchantId).toBe("22222222-2222-4222-8222-222222222222");
    expect(session.state().caller?.roles).toEqual(["OWNER"]);
  });

  it("keeps nothing that could be stolen later", async () => {
    const session = createSession(recording(() => issued()));
    await session.signIn("owner@mizan.local", "a-long-enough-password");

    // The refresh token came back in the body, as it does for every client. Not keeping it
    // is the whole arrangement: what survives a reload is a cookie this page cannot read.
    const everythingReachable = JSON.stringify({
      state: session.state(),
      keys: Object.keys(session),
    });
    expect(everythingReachable).not.toContain("a-refresh-token-nobody-here-should-remember");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("sends the credentials and asks for the cookie to come back", async () => {
    const session = createSession(recording(() => issued()));
    await session.signIn("owner@mizan.local", "a-long-enough-password");

    expect(calls[0]?.path).toBe("/api/v1/tokens");
    expect(calls[0]?.init.credentials).toBe("same-origin");
    expect(String(calls[0]?.init.body)).toContain("owner@mizan.local");
  });

  it("stays signed out when the credentials are refused", async () => {
    const session = createSession(recording(() => refused(401, "UNAUTHORIZED")));

    await expect(session.signIn("owner@mizan.local", "wrong")).rejects.toBeInstanceOf(
      PlatformError,
    );
    expect(session.state().status).toBe("signed-out");
  });
});

describe("staying signed in", () => {
  it("renews once, however many requests find the same expired token", async () => {
    let refreshes = 0;
    let renewed = false;

    const fetchImpl = vi.fn(async (path: string) => {
      if (path === "/api/v1/tokens/refresh") {
        refreshes += 1;
        // Slow on purpose: without single flight, the second request starts its own renewal
        // while this one is still in the air, which is exactly the race being tested.
        await new Promise((resume) => setTimeout(resume, 10));
        renewed = true;
        return issued();
      }
      if (path === "/api/v1/tokens") {
        return issued();
      }
      return renewed ? new Response("[]", { status: 200 }) : refused(401, "UNAUTHORIZED");
    }) as unknown as typeof fetch;

    const session = createSession(fetchImpl);
    await session.signIn("owner@mizan.local", "a-long-enough-password");

    const [first, second] = await Promise.all([
      session.call("/api/v1/merchants/x/payments"),
      session.call("/api/v1/merchants/x/accounts"),
    ]);

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    // A refresh token is single use, and spending two in parallel is indistinguishable from
    // replaying a stolen one: the platform would revoke the family and sign the person out
    // for opening two panels at once.
    expect(refreshes).toBe(1);
  });

  it("renews when it has no token at all, which is what a reload is", async () => {
    const fetchImpl = vi.fn(async (path: string) =>
      path === "/api/v1/tokens/refresh" ? issued() : new Response("[]", { status: 200 }),
    ) as unknown as typeof fetch;

    const session = createSession(fetchImpl);
    await session.restore();

    expect(session.state().status).toBe("signed-in");
    expect(session.state().caller?.roles).toEqual(["OWNER"]);
  });

  it("signs out when the renewal is refused, rather than half signing in", async () => {
    const seen: string[] = [];
    const session = createSession(
      vi.fn(async (path: string) => {
        seen.push(path);
        return refused(401, "UNAUTHORIZED");
      }) as unknown as typeof fetch,
    );

    await session.restore();

    expect(session.state().status).toBe("signed-out");
    expect(session.state().caller).toBeNull();
    expect(seen).toEqual(["/api/v1/tokens/refresh"]);
  });

  it("retries a request once and then believes the answer", async () => {
    let attempts = 0;
    const session = createSession(
      vi.fn(async (path: string) => {
        if (path === "/api/v1/tokens" || path === "/api/v1/tokens/refresh") {
          return issued();
        }
        attempts += 1;
        return refused(401, "UNAUTHORIZED");
      }) as unknown as typeof fetch,
    );

    await session.signIn("owner@mizan.local", "a-long-enough-password");
    const answer = await session.call("/api/v1/merchants/x/payments");

    expect(answer.status).toBe(401);
    // A 401 that survives a fresh token is the platform saying no, not saying "not yet".
    expect(attempts).toBe(2);
  });

  it("tells everybody watching when the session changes", async () => {
    const seen: string[] = [];
    const session = createSession(vi.fn(async () => issued()) as unknown as typeof fetch);
    session.subscribe((state) => seen.push(state.status));

    await session.signIn("owner@mizan.local", "a-long-enough-password");
    await session.signOut();

    expect(seen).toEqual(["signed-in", "signed-out"]);
  });
});

describe("signing out", () => {
  it("tells the platform, so the token is revoked and not merely forgotten", async () => {
    const seen: string[] = [];
    const session = createSession(
      vi.fn(async (path: string) => {
        seen.push(path);
        return path.endsWith("sign-out") ? new Response(null, { status: 204 }) : issued();
      }) as unknown as typeof fetch,
    );

    await session.signIn("owner@mizan.local", "a-long-enough-password");
    await session.signOut();

    expect(seen).toContain("/api/v1/tokens/sign-out");
    expect(session.state().status).toBe("signed-out");
  });

  it("signs out locally even when the platform cannot be reached", async () => {
    const session = createSession(
      vi.fn(async (path: string) => {
        if (path.endsWith("sign-out")) {
          throw new TypeError("network");
        }
        return issued();
      }) as unknown as typeof fetch,
    );

    await session.signIn("owner@mizan.local", "a-long-enough-password");
    await expect(session.signOut()).rejects.toThrow();

    // Somebody at a shared machine pressing sign out has to end up signed out, whatever the
    // network did. The token is revoked on the next attempt; until then the cookie is all
    // that is left, and it is not readable from this page.
    expect(session.state().status).toBe("signed-out");
  });
});
