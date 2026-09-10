import type { ReactNode } from "react";
import { NavLink } from "react-router-dom";
import { useSession } from "../session/SessionProvider";

/** The frame around every signed in page: where else to go, who this is, and the way out. */
export function Shell({ children }: { children: ReactNode }) {
  const { caller, session, can } = useSession();

  return (
    <div className="shell">
      <header>
        <span className="brand">Mizan</span>
        <nav aria-label="Sections">
          {can("PAYMENT_READ") ? <NavLink to="/payments">Payments</NavLink> : null}
          <NavLink to="/account">Account</NavLink>
        </nav>
        <div className="who">
          <span className="muted" title={caller?.userId}>
            {caller?.roles.join(", ").toLowerCase()}
          </span>
          <button type="button" onClick={() => void session.signOut()}>
            Sign out
          </button>
        </div>
      </header>
      {children}
    </div>
  );
}
