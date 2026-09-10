import { useState, type FormEvent } from "react";
import { PlatformError } from "../api/problems";
import { useSession } from "../session/SessionProvider";

/**
 * The way in.
 *
 * Deliberately says as little as the platform does. Identity answers a wrong password and an
 * address nobody has registered identically, so that asking cannot tell you whether somebody
 * has an account here; repeating that answer verbatim is how the console keeps that true.
 */
export function SignIn() {
  const { session } = useSession();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [refusal, setRefusal] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setRefusal(null);
    setBusy(true);

    try {
      await session.signIn(email, password);
    } catch (failed) {
      setRefusal(
        failed instanceof PlatformError
          ? failed.problem.detail
          : "This platform could not be reached. Nothing was sent.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="signin">
      <form onSubmit={submit} aria-labelledby="signin-heading">
        <h1 id="signin-heading">Mizan</h1>
        <p className="muted">The merchant console</p>

        <label htmlFor="email">Email</label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />

        <label htmlFor="password">Password</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />

        {refusal ? (
          <p className="refusal" role="alert">
            {refusal}
          </p>
        ) : null}

        <button type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </main>
  );
}
