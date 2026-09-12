import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Books } from "./books/Books";
import { Dashboard } from "./dashboard/Dashboard";
import { Home } from "./pages/Home";
import { PaymentDetail } from "./payments/PaymentDetail";
import { Payments } from "./payments/Payments";
import { Reviews } from "./reviews/Reviews";
import { Settings } from "./settings/Settings";
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
          <Route path="/" element={<Dashboard />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/payments/:paymentId" element={<PaymentDetail />} />
          <Route path="/reviews" element={<Reviews />} />
          <Route path="/books" element={<Books />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/account" element={<Home />} />
          {/* Anything else is a link somebody kept from a version that had more pages. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  );
}
