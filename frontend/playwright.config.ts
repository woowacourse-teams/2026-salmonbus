import { defineConfig, devices } from "@playwright/test";
import process from "node:process";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  ...(process.env.CI ? { workers: 1 } : {}),
  reporter: "html",
  use: {
    baseURL: "http://127.0.0.1:4173",
    trace: { mode: "retain-on-first-failure", snapshots: false },
    screenshot: "only-on-failure",
    video: "on-first-retry",
    launchOptions: { slowMo: Number(process.env.E2E_SLOW_MO ?? 0) },
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },

    {
      name: "firefox",
      use: { ...devices["Desktop Firefox"] },
    },

    {
      name: "webkit",
      use: { ...devices["Desktop Safari"] },
    },

    {
      name: "iphone-15",
      use: { ...devices["iPhone 15"] },
    },
  ],

  webServer: {
    command: "pnpm dev --host 127.0.0.1 --port 4173",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    // env/.env.development에 Amplitude 키가 있어도 E2E 이벤트가 Amplitude에 쌓이지 않게 비워 둔다.
    env: { WEBPACK_AMPLITUDE_API_KEY: "" },
  },
});
