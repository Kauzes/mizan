import type { Session } from "../session/session";
import { PlatformError, problemFrom } from "./problems";

/**
 * A page of something, and where in the answer it sits.
 *
 * The platform keeps the body as a plain array and puts the paging in headers, so that adding
 * pagination did not break every client parsing a list. This is the half of that arrangement
 * that has to read them.
 */
export interface Page<T> {
  readonly items: readonly T[];
  readonly total: number;
  readonly page: number;
  readonly size: number;
  readonly pages: number;
}

export async function fetchPage<T>(
  session: Session,
  path: string,
  init?: RequestInit,
): Promise<Page<T>> {
  const response = await session.call(path, init);
  if (!response.ok) {
    throw new PlatformError(await problemFrom(response));
  }

  const items = (await response.json()) as T[];
  const size = header(response, "X-Page-Size", items.length || 1);
  const total = header(response, "X-Total-Count", items.length);

  return {
    items,
    total,
    page: header(response, "X-Page", 0),
    size,
    pages: size === 0 ? 0 : Math.ceil(total / size),
  };
}

function header(response: Response, name: string, fallback: number): number {
  const value = Number(response.headers.get(name));
  return Number.isFinite(value) && response.headers.has(name) ? value : fallback;
}
