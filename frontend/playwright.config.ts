import { defineConfig, devices } from "@playwright/test";
import process from "node:process";

const port = 4173;
const baseURL = `http://127.0.0.1:${port}`;

const staticServer = {
  // CI에서도 앞 단계의 빌드를 다시 쓰지 않고, E2E가 검사할 번들을 직접 만든다.
  command: `pnpm build && node e2e/static-server.ts --port ${port}`,
  // 개인 env 파일을 읽지 않아 누가 돌려도 같은 번들을 만든다.
  // 셸에서 export한 Amplitude 키도 비워서 E2E 이벤트가 Amplitude에 쌓이지 않게 한다.
  env: { IGNORE_ENV_FILES: "true", WEBPACK_AMPLITUDE_API_KEY: "" },
};

const devServer = {
  command: `pnpm dev --host 127.0.0.1 --port ${port}`,
  // env/.env.development를 읽어도 E2E 이벤트가 Amplitude에 쌓이지 않고, mock을 빠져나간 요청이 원격 서버로 가지 않게 한다.
  env: { WEBPACK_AMPLITUDE_API_KEY: "", API_PROXY_TARGET: "" },
};

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  ...(process.env.CI ? { workers: 2 } : {}),
  reporter: "html",
  use: {
    baseURL,
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
    ...(process.env.E2E_SERVER === "dev" ? devServer : staticServer),
    url: baseURL,
    // 같은 포트에 다른 워크트리의 서버가 떠 있을 때 그 서버의 코드로 테스트가 돌지 않게 한다.
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
