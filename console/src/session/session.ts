import { PlatformError, problemFrom, type Problem } from "../api/problems";
import { callerIn, type Caller } from "./accessToken";

/**
 * Being signed in, and staying that way.
 *
 * Two decisions hold this together, and both are about where the refresh token is not.
 *
 * The access token lives here, in a closure, for the fifteen minutes it is good for. It is
 * never written to local storage, session storage, a cookie this page can read, or the URL.
 * A reload loses it, which is correct: what survives a reload is the refresh token, and that
 * lives in a cookie this page cannot read at all. So the worst a script that reaches this page
 * can do is use the session while it is open, rather than copy it and keep it.
 *
 * Which means restoring a session is the same operation as renewing one, and there is only one
 * path to get wrong.
 */

export type SessionStatus = "starting" | "signed-in" | "signed-out";

export interface SessionState {
  readonly status: SessionStatus;
  readonly caller: Caller | null;
}

export interface Session {
  state(): SessionState;
  subscribe(listener: (state: SessionState) => void): () => void;
  /** Asks whether the browser is still carrying a session. Called once, on start. */
  restore(): Promise<void>;
  signIn(email: string, password: string): Promise<void>;
  signOut(): Promise<void>;
  /** A request to the platform, with a token on it and one renewal if it has expired. */
  call(path: string, init?: RequestInit): Promise<Response>;
  json<T>(path: string, init?: RequestInit): Promise<T>;
}

const TOKENS = "/api/v1/tokens";

interface Issued {
  accessToken: string;
}

export function createSession(fetchImpl: typeof fetch = globalThis.fetch): Session {
  let accessToken: string | null = null;
  let state: SessionState = { status: "starting", caller: null };
  let renewal: Promise<string> | null = null;
  const listeners = new Set<(state: SessionState) => void>();

  function announce(next: SessionState): void {
    state = next;
    for (const listener of listeners) {
      listener(state);
    }
  }

  function hold(issued: Issued): void {
    accessToken = issued.accessToken;
    announce({ status: "signed-in", caller: callerIn(issued.accessToken) });
  }

  function forget(): void {
    accessToken = null;
    announce({ status: "signed-out", caller: null });
  }

  /**
   * One renewal at a time, however many requests discover the same expired token.
   *
   * This matters more than it looks. A refresh token is single use and replaying one revokes
   * every token descended from that sign in — so two requests refreshing in parallel would
   * spend two tokens, the second of which is the first one's parent, and the platform would
   * correctly conclude that somebody is replaying a stolen token and end the session. The
   * console would have signed the person out by loading two panels at once.
   */
  function renew(): Promise<string> {
    renewal ??= exchange(TOKENS + "/refresh", undefined).finally(() => {
      renewal = null;
    });
    return renewal;
  }

  async function exchange(path: string, body: unknown): Promise<string> {
    const response = await send(path, {
      method: "POST",
      ...(body === undefined
        ? {}
        : { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
    });

    if (!response.ok) {
      // Whatever went wrong, this browser is not signed in any more. Leaving somebody in a
      // half-signed-in state, holding a token that no longer renews, means every page they
      // open fails in its own way instead of once, clearly, here.
      forget();
      throw new PlatformError(await problemFrom(response));
    }

    const issued = (await response.json()) as Issued & { refreshToken?: string };
    // The body still carries a refresh token, for clients that have nowhere better to put
    // one. This is not one of them, and the deliberate act of not keeping it is the point.
    hold(issued);
    return issued.accessToken;
  }

  async function send(path: string, init: RequestInit): Promise<Response> {
    return fetchImpl(path, {
      ...init,
      // The refresh cookie is on this origin and scoped to the token endpoints. Same-origin
      // is the default; saying so keeps it from becoming somebody's convenient change.
      credentials: "same-origin",
      headers: { ...correlated(), ...(init.headers ?? {}) },
    });
  }

  async function withToken(path: string, init: RequestInit, token: string): Promise<Response> {
    return send(path, {
      ...init,
      headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` },
    });
  }

  return {
    state: () => state,

    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    async restore() {
      try {
        await renew();
      } catch {
        // No cookie, or one the platform will not honour. Signing in is what happens next,
        // and `exchange` has already said so.
        forget();
      }
    },

    async signIn(email, password) {
      await exchange(TOKENS, { email, password });
    },

    async signOut() {
      try {
        // Told to the platform rather than only forgotten here, so the refresh token is
        // revoked rather than left working for whoever else has a copy of it.
        await send(TOKENS + "/sign-out", { method: "POST" });
      } finally {
        forget();
      }
    },

    async call(path, init = {}) {
      const token = accessToken ?? (await renew());
      const first = await withToken(path, init, token);

      if (first.status !== 401) {
        return first;
      }

      // Exactly one renewal and exactly one retry. A loop here would turn an expired session
      // into an infinite one, and a 401 that survives a fresh token is the platform saying no
      // rather than saying "not yet".
      return withToken(path, init, await renew());
    },

    async json<T>(path: string, init?: RequestInit): Promise<T> {
      const response = await this.call(path, init);
      if (!response.ok) {
        throw new PlatformError(await problemFrom(response));
      }
      return (await response.json()) as T;
    },
  };
}

/**
 * A correlation id per request, chosen here.
 *
 * The platform generates one if the caller sends none, but then the console has to find it in
 * a response header to be able to repeat it — and a request that failed before there was a
 * response has none at all. Choosing it here means the id in a support conversation is the
 * same id in the logs, whatever happened.
 */
function correlated(): Record<string, string> {
  return { "X-Correlation-Id": crypto.randomUUID() };
}

export type { Problem };
