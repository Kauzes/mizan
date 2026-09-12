import { useCallback, useEffect, useState } from "react";
import { PlatformError, problemFrom } from "../api/problems";
import { useSession } from "../session/SessionProvider";
import { Secret } from "./Secret";

/**
 * The keys a merchant's own servers authenticate with.
 *
 * Every destructive thing here names what will stop working before it happens. A revoked key
 * is somebody's production integration, and "are you sure" does not tell them which one they
 * are about to break.
 */
interface Key {
  readonly id: string;
  readonly keyId: string;
  readonly name: string;
  readonly role: string;
  readonly createdAt: string;
  readonly lastUsedAt: string | null;
  readonly revokedAt: string | null;
  readonly rotatedFrom: string | null;
}

const ROLES = ["VIEWER", "ANALYST", "ADMIN", "OWNER"] as const;

export function Keys() {
  const { session, caller, can } = useSession();
  const merchantId = caller?.merchantId;

  const [keys, setKeys] = useState<readonly Key[] | null>(null);
  const [issued, setIssued] = useState<{ label: string; secret: string } | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [role, setRole] = useState<string>("VIEWER");
  const [busy, setBusy] = useState(false);
  const [revoking, setRevoking] = useState<Key | null>(null);
  const [reread, setReread] = useState(0);

  useEffect(() => {
    if (!merchantId || !can("API_KEY_MANAGE")) {
      return;
    }
    let current = true;

    session
      .json<Key[]>(`/api/v1/merchants/${merchantId}/api-keys`)
      .then((theirs) => current && setKeys(theirs))
      .catch((failed: unknown) => current && setRefusal(said(failed)));

    return () => {
      current = false;
    };
  }, [session, merchantId, can, reread]);

  const send = useCallback(
    async (path: string, init: RequestInit): Promise<unknown> => {
      const response = await session.call(`/api/v1/merchants/${merchantId}${path}`, {
        ...init,
        headers: { "Content-Type": "application/json", ...(init.headers ?? {}) },
      });
      if (!response.ok) {
        throw new PlatformError(await problemFrom(response));
      }
      return response.status === 204 ? null : await response.json();
    },
    [session, merchantId],
  );

  if (!can("API_KEY_MANAGE")) {
    return null;
  }

  async function issue() {
    setBusy(true);
    setRefusal(null);
    try {
      const answer = (await send("/api-keys", {
        method: "POST",
        body: JSON.stringify({ name: name.trim(), role }),
      })) as { key: Key; secret: string };

      setIssued({ label: `The secret for ${answer.key.keyId}`, secret: answer.secret });
      setName("");
      setReread((count) => count + 1);
    } catch (failed) {
      setRefusal(said(failed));
    } finally {
      setBusy(false);
    }
  }

  async function rotate(key: Key) {
    setRefusal(null);
    try {
      const answer = (await send(`/api-keys/${key.id}/rotate`, { method: "POST" })) as {
        key: Key;
        secret: string;
      };
      setIssued({ label: `The new secret for ${answer.key.keyId}`, secret: answer.secret });
      setReread((count) => count + 1);
    } catch (failed) {
      setRefusal(said(failed));
    }
  }

  async function revoke(key: Key) {
    setRefusal(null);
    try {
      await send(`/api-keys/${key.id}`, { method: "DELETE" });
      setRevoking(null);
      setReread((count) => count + 1);
    } catch (failed) {
      setRefusal(said(failed));
    }
  }

  return (
    <section>
      <h2>API keys</h2>
      <p className="muted">
        What a merchant's own server signs its requests with. One role per key: an integration
        does one job, and a key that can do everything is a key that will.
      </p>

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}

      {issued ? (
        <Secret
          label={issued.label}
          secret={issued.secret}
          onDismissed={() => setIssued(null)}
        />
      ) : null}

      <form
        className="issue"
        onSubmit={(event) => {
          event.preventDefault();
          if (name.trim()) {
            void issue();
          }
        }}
      >
        <label htmlFor="key-name">What is it for</label>
        <input
          id="key-name"
          value={name}
          placeholder="nightly reconciliation"
          onChange={(event) => setName(event.target.value)}
        />
        <label htmlFor="key-role">May act as</label>
        <select id="key-role" value={role} onChange={(event) => setRole(event.target.value)}>
          {ROLES.map((one) => (
            <option key={one}>{one}</option>
          ))}
        </select>
        {/* Said before the key exists rather than after the secret has scrolled away. */}
        <span className="muted">The secret is shown once, when it is issued.</span>
        <button type="submit" disabled={busy || !name.trim()}>
          Issue a key
        </button>
      </form>

      {keys === null ? <p className="muted">Looking…</p> : null}
      {keys?.length === 0 ? <p className="muted">No keys yet.</p> : null}

      {keys && keys.length > 0 ? (
        <table className="keys">
          <thead>
            <tr>
              <th scope="col">Key</th>
              <th scope="col">For</th>
              <th scope="col">Acts as</th>
              <th scope="col">Last used</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {keys.map((key) => (
              <tr key={key.id} className={key.revokedAt ? "revoked" : undefined}>
                <td>
                  <code>{key.keyId}</code>
                </td>
                <td>{key.name}</td>
                <td className="muted">{key.role.toLowerCase()}</td>
                <td className="muted">
                  {key.revokedAt
                    ? "revoked"
                    : key.lastUsedAt
                      ? new Date(key.lastUsedAt).toLocaleString()
                      : "never"}
                </td>
                <td>
                  {key.revokedAt ? null : (
                    <>
                      <button type="button" onClick={() => void rotate(key)}>
                        Rotate
                      </button>{" "}
                      <button type="button" onClick={() => setRevoking(key)}>
                        Revoke
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {revoking ? (
        <div className="confirm" role="group" aria-label="Confirm revoking this key">
          <p>
            Revoking <code>{revoking.keyId}</code> ({revoking.name}) stops every request signed
            with it, immediately. Anything using it will start being refused.
          </p>
          <p>
            <button type="button" onClick={() => void revoke(revoking)}>
              Revoke it
            </button>{" "}
            <button type="button" onClick={() => setRevoking(null)}>
              Keep it
            </button>
          </p>
        </div>
      ) : null}
    </section>
  );
}

function said(failed: unknown): string {
  return failed instanceof PlatformError
    ? failed.problem.detail
    : "This platform could not be reached.";
}
