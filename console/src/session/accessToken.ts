/**
 * Reading the access token, without pretending to verify it.
 *
 * The gateway verifies the signature; it is the only thing on this platform that may, because
 * it is the only one holding the published key and the only one a caller cannot go around.
 * What happens here is decoding, so the console can put a name on the screen and know which
 * merchant to ask about. Nothing decided here is a security decision: a person who edits their
 * own token to say OWNER sees more buttons and is refused by every one of them.
 */

export interface Caller {
  readonly userId: string;
  readonly merchantId: string;
  readonly roles: readonly string[];
  /** When the token stops being accepted, as a moment rather than a duration. */
  readonly expiresAt: Date;
}

export function callerIn(accessToken: string): Caller | null {
  const payload = payloadOf(accessToken);
  if (!payload) {
    return null;
  }

  const { sub, merchant, roles, exp } = payload;
  if (typeof sub !== "string" || typeof merchant !== "string" || typeof exp !== "number") {
    return null;
  }

  return {
    userId: sub,
    merchantId: merchant,
    roles: Array.isArray(roles) ? roles.filter((role) => typeof role === "string") : [],
    expiresAt: new Date(exp * 1000),
  };
}

function payloadOf(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3 || !parts[1]) {
    return null;
  }

  try {
    // Base64url, which is not what atob reads: the two characters differ and the padding is
    // gone. Every token this platform issues arrives this way, so it is not an edge case.
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), "=");
    const decoded = JSON.parse(
      new TextDecoder().decode(Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))),
    ) as unknown;

    return decoded && typeof decoded === "object" ? (decoded as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}
