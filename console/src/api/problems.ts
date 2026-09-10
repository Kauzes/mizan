/**
 * What the platform says when it refuses, and how this console reads it.
 *
 * Every error from every service is an RFC 9457 problem detail carrying a `code` from a closed
 * set. Branching on that code rather than on the status or the message is the contract the API
 * documentation asks callers to keep, and this is the console keeping it.
 */

/** The platform's error body. Every field here is on every refusal. */
export interface Problem {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail: string;
  readonly code: string;
  readonly correlationId: string;
  readonly errors?: ReadonlyArray<{ field: string; message: string }>;
}

/** A refusal, carrying what the platform said about it. */
export class PlatformError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail || problem.title);
    this.name = "PlatformError";
  }

  /** Whether this is worth trying again, which only the code can answer. */
  get worthRetrying(): boolean {
    return (
      this.problem.code === "UPSTREAM_UNAVAILABLE" ||
      this.problem.code === "UPSTREAM_TIMEOUT" ||
      this.problem.code === "CONTENDED"
    );
  }
}

/**
 * Reads a refusal, whatever actually came back.
 *
 * A response that is not a problem detail is still turned into one. Something has to be shown
 * to the person in front of the screen, and "undefined" is not it — the gateway itself was the
 * last thing on this platform that could answer without a code, and MIZ-46 fixed that.
 */
export async function problemFrom(response: Response): Promise<Problem> {
  const fallback: Problem = {
    type: "about:blank",
    title: response.statusText || "error",
    status: response.status,
    detail: "Something went wrong, and this platform did not say what.",
    code: "INTERNAL_ERROR",
    correlationId: response.headers.get("X-Correlation-Id") ?? "",
  };

  try {
    const body: unknown = await response.json();
    if (body && typeof body === "object" && "code" in body) {
      return { ...fallback, ...(body as Partial<Problem>) } as Problem;
    }
    return fallback;
  } catch {
    return fallback;
  }
}
