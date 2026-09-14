import { useState } from "react";

/**
 * The trace id of one step, for handing to somebody who can open it.
 *
 * The person using this is on the telephone. They have a merchant asking why a payment took
 * eleven seconds, and what they need is not to debug it themselves — it is to give whoever
 * can a thing to paste into a search box. So the id is here, next to the step it belongs to,
 * and one click puts it on the clipboard.
 *
 * Shown short and made whole only on copy. Thirty-two hexadecimal characters on every row of
 * a timeline would drown the words, and the words are what the person is reading.
 *
 * Deliberately not a link. Where the traces are kept is a fact about a deployment, and a
 * console that hard coded one would show a broken link everywhere else it ran.
 */
export function Trace({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // A browser that refuses the clipboard is not worth a message: the id is on the screen
      // and can be selected. Saying "could not copy" would only be alarming.
      setCopied(false);
    }
  }

  return (
    <button
      type="button"
      className="trace"
      onClick={() => void copy()}
      // The whole id in the label and eight characters on screen. A screen reader announcing
      // "trace 4bf92f35" would be announcing a prefix, which is not an id anybody can use.
      aria-label={`Trace ${id} — copy it for whoever is looking`}
      title={`Trace ${id} — copy it for whoever is looking`}
    >
      {copied ? "copied" : `trace ${id.slice(0, 8)}`}
    </button>
  );
}
