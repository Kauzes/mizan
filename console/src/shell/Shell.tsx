import type { ReactNode } from "react";
import { useSession } from "../session/SessionProvider";

/** The frame around every signed in page: who this is, and the way out. */
export function Shell({ children }: { children: ReactNode }) {
  const { caller, session } = useSession();

  return (
    <div className="shell">
      <header>
        <span className="brand">Mizan</span>
        <nav aria-label="Sections">
          <span className="muted">Payments, reviews and the books arrive next</span>
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
