import { defineConfig, devices } from "@playwright/test";

/**
 * The browser journey, against the Compose stack.
 *
 * Nothing is stubbed and nothing is seeded from a fixture: every request in this run reaches
 * a real service in the image it is deployed as. The smoke check already proves the platform
 * works without a browser; this proves the console does not lie about it.
 *
 * There is no webServer here on purpose. The console under test is the one in the Compose
 * stack, served by nginx with the API on the same origin — which is the arrangement the
 * session cookie depends on, and starting a dev server instead would test a different
 * platform from the one being shipped.
 */
export default defineConfig({
  testDir: "./e2e",
  // One worker. The journey registers its own merchant so it could run beside itself, but a
  // failure that only happens under load is a failure about Postgres connections rather than
  // about the console, and this suite is not where that should be discovered.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: process.env.MIZAN_CONSOLE ?? "http://localhost:5173",
    // Something to look at when CI goes red. A failing browser test nobody can reproduce is
    // a test people learn to rerun.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
