/**
 * What the person signed in may actually do.
 *
 * The table comes from the platform, at /api/v1/roles, rather than being written out again in
 * TypeScript. A copy here would be right on the day it was typed and wrong on the day somebody
 * adds a permission — and wrong in the worst direction, offering an action that is refused.
 *
 * None of this is a security decision. The services decide; this decides what is worth showing,
 * and a person who tampers with their own token to see more buttons is refused by every one.
 */

export interface AccessModel {
  readonly roles: Readonly<Record<string, readonly string[]>>;
  readonly permissions: readonly string[];
}

/** Nothing is permitted until the table has been fetched, which is the safe way round. */
export const NOTHING_YET: AccessModel = { roles: {}, permissions: [] };

export function permissionsOf(model: AccessModel, roles: readonly string[]): Set<string> {
  const held = new Set<string>();
  for (const role of roles) {
    for (const permission of model.roles[role] ?? []) {
      held.add(permission);
    }
  }
  return held;
}

export function can(
  model: AccessModel,
  roles: readonly string[],
  permission: string,
): boolean {
  return permissionsOf(model, roles).has(permission);
}
