import { describe, expect, it } from "vitest";
import { can, NOTHING_YET, permissionsOf } from "./roles";

const platform = {
  roles: {
    OWNER: ["PAYMENT_READ", "PAYMENT_WRITE", "REVIEW_RULE", "USER_MANAGE"],
    ANALYST: ["PAYMENT_READ", "REVIEW_RULE"],
    VIEWER: ["PAYMENT_READ"],
  },
  permissions: ["PAYMENT_READ", "PAYMENT_WRITE", "REVIEW_RULE", "USER_MANAGE"],
};

describe("what somebody may do", () => {
  it("is the union of what their roles allow", () => {
    expect([...permissionsOf(platform, ["VIEWER", "ANALYST"])].sort()).toEqual([
      "PAYMENT_READ",
      "REVIEW_RULE",
    ]);
    expect(can(platform, ["ANALYST"], "REVIEW_RULE")).toBe(true);
    expect(can(platform, ["ANALYST"], "PAYMENT_WRITE")).toBe(false);
  });

  it("is nothing at all before the platform has said", () => {
    // The safe way round. A console that assumed permission until told otherwise would show
    // an owner's buttons to a viewer for as long as one request takes.
    expect(can(NOTHING_YET, ["OWNER"], "PAYMENT_WRITE")).toBe(false);
  });

  it("ignores a role the platform does not know about", () => {
    // The same rule the services follow: an unknown name grants nothing rather than being
    // refused, so deploying a console that knows a new role first cannot lock anybody out.
    expect(can(platform, ["SOMETHING_NEW"], "PAYMENT_READ")).toBe(false);
    expect(can(platform, ["SOMETHING_NEW", "VIEWER"], "PAYMENT_READ")).toBe(true);
  });
});
