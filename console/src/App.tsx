import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Home } from "./pages/Home";
import { Payments } from "./payments/Payments";
import { SignIn } from "./pages/SignIn";
import { useSession } from "./session/SessionProvider";
import { Shell } from "./shell/Shell";

/**
 * Signed in or not, and nothing in between.
 *
 * The third state is the one that matters: on the first paint the console does not yet know,
 * because it is still asking whether the browser is carrying a session. Rendering the sign in
 * form during that moment would flash a login screen at somebody who is already signed in,
 * every time they reload.
 */
export function App() {
  const { status } = useSession();

  if (status === "starting") {
    return (
      <main className="starting" aria-busy="true">
        <p className="muted">Just a moment…</p>
      </main>
    );
  }

  if (status !== "signed-in") {
    return <SignIn />;
  }

  return (
    <BrowserRouter>
      <Shell>
        <Routes>
          <Route path="/payments" element={<Payments />} />
          <Route path="/account" element={<Home />} />
          <Route path="/" element={<Navigate to="/payments" replace />} />
          {/* Anything else is a link somebody kept from a version that had more pages. */}
          <Route path="*" element={<Navigate to="/payments" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  );
}
