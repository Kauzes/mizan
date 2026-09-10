import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { NOTHING_YET, can as roleCan, type AccessModel } from "../access/roles";
import { createSession, type Session, type SessionState } from "./session";

/**
 * The session, as the rest of the console sees it.
 *
 * One instance for the whole application, created once. Everything that talks to the platform
 * goes through it, so there is exactly one place that knows about tokens and exactly one place
 * that can be wrong about them.
 */
interface SessionContextValue extends SessionState {
  readonly session: Session;
  /** Whether the person signed in holds a permission. Never a security decision here. */
  can(permission: string): boolean;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({
  children,
  session: given,
}: {
  children: ReactNode;
  /** Injected by tests. Nothing else has a reason to pass one. */
  session?: Session;
}) {
  const session = useRef(given ?? createSession()).current;
  const [state, setState] = useState<SessionState>(session.state());
  const [access, setAccess] = useState<AccessModel>(NOTHING_YET);

  useEffect(() => {
    const stop = session.subscribe(setState);
    void session.restore();
    return stop;
  }, [session]);

  useEffect(() => {
    if (state.status !== "signed-in" || access !== NOTHING_YET) {
      return;
    }
    // Fetched once a session exists and kept for as long as it does. The table is a constant
    // of the platform rather than of the merchant, so refetching it would only be noise.
    void session
      .json<AccessModel>("/api/v1/roles")
      .then(setAccess)
      .catch(() => setAccess(NOTHING_YET));
  }, [session, state.status, access]);

  const value = useMemo<SessionContextValue>(
    () => ({
      ...state,
      session,
      can: (permission) => roleCan(access, state.caller?.roles ?? [], permission),
    }),
    [state, session, access],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error("useSession was called outside the provider that creates one");
  }
  return value;
}
