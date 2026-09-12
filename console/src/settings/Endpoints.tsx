import { useCallback, useEffect, useState } from "react";
import { PlatformError, problemFrom } from "../api/problems";
import type { Delivery } from "../payments/types";
import { useSession } from "../session/SessionProvider";
import { Secret } from "./Secret";

/**
 * Where a merchant is told what happened, and what happened when this platform tried.
 *
 * The delivery history is the part that earns this page. A merchant whose endpoint was down
 * for an hour needs to see the hour — every attempt, what their own server answered — rather
 * than a number that says "3 failures" and leaves them guessing which three.
 */
interface Endpoint {
  readonly id: string;
  readonly url: string;
  readonly description: string | null;
  readonly eventTypes: readonly string[];
  readonly enabled: boolean;
  readonly secretRotatedAt: string | null;
  readonly createdAt: string;
}

const EVERY_EVENT = [
  "payment.authorized",
  "payment.captured",
  "payment.declined",
  "payment.refunded",
  "payment.voided",
] as const;

export function Endpoints() {
  const { session, caller, can } = useSession();
  const merchantId = caller?.merchantId;

  const [endpoints, setEndpoints] = useState<readonly Endpoint[] | null>(null);
  const [secret, setSecret] = useState<{ label: string; secret: string } | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [url, setUrl] = useState("");
  const [description, setDescription] = useState("");
  const [wanted, setWanted] = useState<string[]>(["payment.captured"]);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<Endpoint | null>(null);
  const [showing, setShowing] = useState<string | null>(null);
  const [reread, setReread] = useState(0);

  const manage = can("WEBHOOK_MANAGE");

  useEffect(() => {
    if (!merchantId || !can("WEBHOOK_READ")) {
      return;
    }
    let current = true;

    session
      .json<Endpoint[]>(`/api/v1/merchants/${merchantId}/webhook-endpoints`)
      .then((theirs) => current && setEndpoints(theirs))
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

  if (!can("WEBHOOK_READ")) {
    return null;
  }

  async function act(what: () => Promise<void>) {
    setRefusal(null);
    try {
      await what();
      setReread((count) => count + 1);
    } catch (failed) {
      setRefusal(said(failed));
    }
  }

  async function register() {
    setBusy(true);
    try {
      const answer = (await send("/webhook-endpoints", {
        method: "POST",
        body: JSON.stringify({
          url: url.trim(),
          description: description.trim() || null,
          eventTypes: wanted,
        }),
      })) as { endpoint: Endpoint; secret: string };

      setSecret({
        label: `The signing secret for ${answer.endpoint.url}`,
        secret: answer.secret,
      });
      setUrl("");
      setDescription("");
      setReread((count) => count + 1);
    } catch (failed) {
      setRefusal(said(failed));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>Webhook endpoints</h2>
      <p className="muted">
        Where this platform tells your servers what happened, without them having to ask.
        Every delivery is signed with the endpoint's own secret.
      </p>

      {refusal ? (
        <p className="refusal" role="alert">
          {refusal}
        </p>
      ) : null}

      {secret ? (
        <Secret
          label={secret.label}
          secret={secret.secret}
          onDismissed={() => setSecret(null)}
        />
      ) : null}

      {manage ? (
        <form
          className="issue"
          onSubmit={(event) => {
            event.preventDefault();
            if (url.trim() && wanted.length > 0) {
              void register();
            }
          }}
        >
          <label htmlFor="endpoint-url">Where to send</label>
          <input
            id="endpoint-url"
            value={url}
            placeholder="https://api.example.com/webhooks/mizan"
            onChange={(event) => setUrl(event.target.value)}
          />
          <label htmlFor="endpoint-description">What it is</label>
          <input
            id="endpoint-description"
            value={description}
            placeholder="Production order service"
            onChange={(event) => setDescription(event.target.value)}
          />
          <span className="muted">The signing secret is shown once, when it is registered.</span>
          <button type="submit" disabled={busy || !url.trim() || wanted.length === 0}>
            Register
          </button>

          <fieldset>
            <legend>Send it</legend>
            {EVERY_EVENT.map((event) => (
              <label key={event}>
                <input
                  type="checkbox"
                  checked={wanted.includes(event)}
                  onChange={(changed) =>
                    setWanted(
                      changed.target.checked
                        ? [...wanted, event]
                        : wanted.filter((one) => one !== event),
                    )
                  }
                />
                {event}
              </label>
            ))}
          </fieldset>
        </form>
      ) : null}

      {endpoints === null ? <p className="muted">Looking…</p> : null}
      {endpoints?.length === 0 ? (
        <p className="muted">No endpoints yet, so nothing is being told anything.</p>
      ) : null}

      {endpoints?.map((endpoint) => (
        <div key={endpoint.id} className={endpoint.enabled ? "endpoint" : "endpoint off"}>
          <p>
            <code>{endpoint.url}</code>{" "}
            {endpoint.enabled ? null : <span className="muted">(disabled)</span>}
          </p>
          <p className="muted">
            {endpoint.description ? `${endpoint.description} · ` : ""}
            {[...endpoint.eventTypes].sort().join(", ")}
          </p>

          <p>
            <button
              type="button"
              onClick={() => setShowing(showing === endpoint.id ? null : endpoint.id)}
            >
              {showing === endpoint.id ? "Hide deliveries" : "Deliveries"}
            </button>{" "}
            {manage ? (
              <>
                <button
                  type="button"
                  onClick={() =>
                    void act(async () => {
                      const answer = (await send(`/webhook-endpoints/${endpoint.id}/secret`, {
                        method: "POST",
                      })) as { endpoint: Endpoint; secret: string };
                      setSecret({
                        label: `The new signing secret for ${endpoint.url}`,
                        secret: answer.secret,
                      });
                    })
                  }
                >
                  Rotate secret
                </button>{" "}
                <button
                  type="button"
                  onClick={() =>
                    void act(() =>
                      send(`/webhook-endpoints/${endpoint.id}`, {
                        method: "PATCH",
                        body: JSON.stringify({ enabled: !endpoint.enabled }),
                      }).then(() => undefined),
                    )
                  }
                >
                  {endpoint.enabled ? "Disable" : "Enable"}
                </button>{" "}
                <button type="button" onClick={() => setRemoving(endpoint)}>
                  Remove
                </button>
              </>
            ) : null}
          </p>

          {showing === endpoint.id ? <History endpoint={endpoint} /> : null}
        </div>
      ))}

      {removing ? (
        <div className="confirm" role="group" aria-label="Confirm removing this endpoint">
          <p>
            Removing <code>{removing.url}</code> stops every delivery to it and takes its
            history with it. Disabling keeps both, and can be undone.
          </p>
          <p>
            <button
              type="button"
              onClick={() =>
                void act(async () => {
                  await send(`/webhook-endpoints/${removing.id}`, { method: "DELETE" });
                  setRemoving(null);
                })
              }
            >
              Remove it
            </button>{" "}
            <button type="button" onClick={() => setRemoving(null)}>
              Keep it
            </button>
          </p>
        </div>
      ) : null}
    </section>
  );
}

/** What was sent to one endpoint, and what their server said about it. */
function History({ endpoint }: { endpoint: { id: string } }) {
  const { session, caller, can } = useSession();
  const [deliveries, setDeliveries] = useState<readonly Delivery[] | null>(null);
  const [redelivered, setRedelivered] = useState<string | null>(null);

  useEffect(() => {
    if (!caller) {
      return;
    }
    let current = true;

    session
      .json<Delivery[]>(
        `/api/v1/merchants/${caller.merchantId}/webhook-endpoints/${endpoint.id}/deliveries?limit=25`,
      )
      .then((sent) => current && setDeliveries(sent))
      .catch(() => current && setDeliveries([]));

    return () => {
      current = false;
    };
  }, [session, caller, endpoint.id, redelivered]);

  if (deliveries === null) {
    return <p className="muted">Looking…</p>;
  }
  if (deliveries.length === 0) {
    return <p className="muted">Nothing has been sent here yet.</p>;
  }

  return (
    <ul className="plain deliveries">
      {deliveries.map((delivery) => (
        <li key={delivery.id}>
          <code>{delivery.event_type}</code> · {delivery.status.toLowerCase()}
          {delivery.last_status_code ? ` · answered ${delivery.last_status_code}` : ""}
          {delivery.attempts > 1 ? (
            <span className="muted"> after {delivery.attempts} attempts</span>
          ) : null}
          {delivery.last_error ? (
            <span className="muted"> — {delivery.last_error}</span>
          ) : null}
          {can("WEBHOOK_MANAGE") && delivery.status !== "DELIVERED" ? (
            <>
              {" "}
              <button
                type="button"
                className="as-link"
                onClick={() => {
                  void session
                    .call(
                      `/api/v1/merchants/${caller?.merchantId}/webhook-endpoints/${endpoint.id}/deliveries/${delivery.id}/redeliver`,
                      { method: "POST" },
                    )
                    .then(() => setRedelivered(delivery.id));
                }}
              >
                send again
              </button>
            </>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function said(failed: unknown): string {
  return failed instanceof PlatformError
    ? failed.problem.detail
    : "This platform could not be reached.";
}
