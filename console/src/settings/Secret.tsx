import { useState } from "react";

/**
 * A secret, shown once.
 *
 * Both secrets on this platform are issued once and never returned again, which puts the
 * whole weight of "did the person actually keep it" on this component. So it says so before
 * the secret is generated as well as after, it is never written anywhere the browser
 * remembers, and dismissing it takes a deliberate confirmation rather than a click on
 * whatever was nearest.
 *
 * Nothing here goes into local storage, a URL, or a log. It is rendered, copied by the
 * person, and gone on navigation — which is also why the copy button is worth having: the
 * alternative is somebody pasting it somewhere to read it later.
 */
export function Secret({
  label,
  secret,
  onDismissed,
}: {
  label: string;
  secret: string;
  onDismissed: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const [sure, setSure] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
    } catch {
      // A browser that refuses the clipboard is not a failure worth a message: the secret is
      // on the screen and can be selected. Saying "could not copy" would only be alarming.
      setCopied(false);
    }
  }

  return (
    <div className="secret" role="group" aria-label={label}>
      <p>
        <strong>{label}</strong>
      </p>
      <p className="muted">
        This is the only time it is shown. It is stored encrypted and no endpoint gives it
        back: if it is lost, the only way forward is to rotate.
      </p>

      <p>
        <code className="secret-value">{secret}</code>
      </p>

      <p>
        <button type="button" onClick={() => void copy()}>
          {copied ? "Copied" : "Copy"}
        </button>{" "}
        {!sure ? (
          <button type="button" onClick={() => setSure(true)}>
            Done
          </button>
        ) : (
          <>
            <span className="muted">Hide it? It cannot be shown again. </span>
            <button type="button" onClick={onDismissed}>
              Yes, I have kept it
            </button>{" "}
            <button type="button" onClick={() => setSure(false)}>
              Not yet
            </button>
          </>
        )}
      </p>
    </div>
  );
}
