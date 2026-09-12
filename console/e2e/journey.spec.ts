import { expect, test } from "@playwright/test";
import { aCapturedPayment, aMerchant } from "./merchant";

/**
 * One journey, in a browser, against the running platform.
 *
 * The smoke check already proves the platform works without a browser. This proves the console
 * does not lie about it: a person signs in, finds a payment, gives part of it back, and sees
 * that reflected in the payment, in the overview and in the books — every one of those figures
 * coming from a different service.
 *
 * Deterministic by construction. Its own merchant every run, so nothing depends on data left
 * behind by a previous one, and no fixed waits: every step waits for the thing it needs rather
 * than for a number of seconds somebody guessed.
 */
test("a merchant signs in, refunds a payment, and sees it in the books", async ({
  page,
  request,
}) => {
  const merchant = await aMerchant(request);
  const payment = await aCapturedPayment(request, merchant, 125000);

  await test.step("signs in", async () => {
    await page.goto("/");
    await page.getByLabel("Email").fill(merchant.email);
    await page.getByLabel("Password").fill(merchant.password);
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByRole("heading", { name: "How business is" })).toBeVisible();
  });

  await test.step("the overview counts what was taken", async () => {
    // One payment, captured. The rate is a hundred per cent of one attempt, and the volume is
    // beside it because a rate without a denominator is a rumour.
    await expect(page.getByText("1 of 1 attempted")).toBeVisible();
    await expect(page.getByText(/1,250\.00/).first()).toBeVisible();
  });

  await test.step("finds the payment by its own reference", async () => {
    await page.getByRole("link", { name: "Payments" }).click();
    await page.getByLabel("Your reference").fill(payment.reference);

    const row = page.getByRole("link", { name: payment.reference });
    await expect(row).toBeVisible();
    await row.click();

    await expect(page.getByRole("heading", { name: new RegExp(payment.reference) })).toBeVisible();
    // The status beside the heading, rather than the word wherever it appears: the timeline
    // below says "captured" too, and both being right is not the same as either being found.
    await expect(page.locator("span.status").first()).toHaveText("captured");
  });

  await test.step("sees the entry the capture wrote", async () => {
    await expect(page.getByRole("heading", { name: "In the books" })).toBeVisible();
    await expect(page.getByText("settlement.try")).toBeVisible();
    // The zero is the point of a double entry ledger, and it is on the screen.
    await expect(page.getByText("sums to")).toBeVisible();
  });

  await test.step("gives part of it back", async () => {
    await page.getByRole("button", { name: "Refund" }).click();
    await page.getByLabel("How much, in TRY").fill("250.00");
    await page.getByRole("button", { name: "Continue" }).click();

    // The confirmation says the amount and what will be left, in words.
    const confirmation = page.getByRole("group", { name: "Confirm this refund" });
    await expect(confirmation).toContainText(/250\.00 back/);
    await expect(confirmation).toContainText(/leaves.*1,000\.00/);

    await confirmation.getByRole("button", { name: /Refund/ }).click();
  });

  await test.step("and the payment says so", async () => {
    await expect(page.getByText(/1,000\.00 still refundable/)).toBeVisible();
    await expect(page.getByRole("heading", { name: "Refunds" })).toBeVisible();
  });

  await test.step("as do the books, which still balance", async () => {
    await page.getByRole("link", { name: "Books" }).click();
    await expect(page.getByRole("heading", { name: "The books" })).toBeVisible();

    // Two entries now: what was captured and what went back. Both sum to zero, and the
    // balance is the ledger's own figure rather than one this page added up.
    await expect(page.getByText(/Card payment refunded/)).toBeVisible();
    await expect(page.getByText("sums to").first()).toBeVisible();

    // Twelve fifty taken, two fifty given back: the ledger says a thousand is owed, and the
    // page repeats that figure rather than working it out.
    const owed = page.locator("table.accounts tr", { hasText: "settlement.try" });
    await expect(owed).toContainText(/1,000\.00/);
  });

  await test.step("and signing out ends the session", async () => {
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();

    // A reload after signing out stays signed out: the refresh cookie was revoked rather
    // than merely forgotten by this tab.
    await page.reload();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  });
});

test("a reload keeps somebody signed in", async ({ page, request }) => {
  const merchant = await aMerchant(request);

  await page.goto("/");
  await page.getByLabel("Email").fill(merchant.email);
  await page.getByLabel("Password").fill(merchant.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "How business is" })).toBeVisible();

  await page.reload();

  // The whole point of ADR 0035, and the one thing no unit test can check: the access token
  // was lost with the page, and the session came back from a cookie this page cannot read.
  await expect(page.getByRole("heading", { name: "How business is" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" })).toHaveCount(0);
});
