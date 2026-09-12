import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/**
 * The console in development, and how it reaches the platform.
 *
 * Everything goes through the gateway, on this origin. The proxy is not a convenience: the
 * refresh token lives in a cookie scoped to /api/v1/tokens, and a cookie is only sent to the
 * origin that set it. Talking to http://localhost:8080 from a page on :5173 would be a
 * different origin, and the session would not survive a reload — which is exactly the thing
 * this arrangement exists to make work.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: false },
    },
  },
  test: {
    // Only the component and unit tests. The browser journey in e2e is Playwright's, and
    // vitest trying to collect it fails in a way that says nothing about either.
    include: ["src/**/*.test.{ts,tsx}"],
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    css: false,
  },
});
