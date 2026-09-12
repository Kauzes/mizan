import { useSession } from "../session/SessionProvider";
import { Endpoints } from "./Endpoints";
import { Keys } from "./Keys";

/**
 * The two things a merchant integrating with this platform has to manage themselves.
 *
 * Both hand out a secret that is shown once, which is the one behaviour this whole page is
 * arranged around: see {@link Secret}.
 */
export function Settings() {
  const { can } = useSession();

  if (!can("API_KEY_MANAGE") && !can("WEBHOOK_READ")) {
    return (
      <main>
        <h1>Settings</h1>
        <p className="muted">
          This account manages neither keys nor endpoints. Both are the owner's to hand out.
        </p>
      </main>
    );
  }

  return (
    <main>
      <h1>Settings</h1>
      <Keys />
      <Endpoints />
    </main>
  );
}
