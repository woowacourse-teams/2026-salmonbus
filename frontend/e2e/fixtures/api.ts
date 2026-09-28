import { test as base, expect } from "@playwright/test";
import { apiPaths, createApiData, observedAt, pollIntervalMs } from "./mock-data";

type Endpoint = keyof typeof apiPaths;
type ResponseMode = "success" | "network-error";

interface MockApi {
  data: ReturnType<typeof createApiData>;
  mode: Record<Endpoint, ResponseMode>;
  requests: Record<Endpoint, number>;
}

export const test = base.extend<{ api: MockApi }>({
  serviceWorkers: "block",
  api: [
    async ({ page }, use) => {
      // 페이지를 열기 전에 시계를 설치한다. 폴링 테스트만 명시적으로 시간을 진행시킨다.
      const start = new Date(observedAt).getTime();
      await page.clock.install({ time: start });
      await page.clock.pauseAt(start + 1_000);

      const api: MockApi = {
        data: createApiData(),
        mode: { routes: "success", board: "success", vehicles: "success" },
        requests: { routes: 0, board: 0, vehicles: 0 },
      };
      const unexpectedRequests: string[] = [];

      await page.route("**/api/**", async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        const endpoint = (Object.keys(apiPaths) as Endpoint[]).find((key) => apiPaths[key] === path);
        if (endpoint === undefined || request.method() !== "GET") {
          unexpectedRequests.push(`${request.method()} ${path}`);
          await route.abort();
          return;
        }

        api.requests[endpoint] += 1;
        if (api.mode[endpoint] === "network-error") {
          await route.abort("failed");
          return;
        }

        // Date 헤더도 브라우저의 제어된 시간과 맞춰 서버 기준 시계가 어긋나지 않게 한다.
        const now = await page.evaluate(() => Date.now());
        await route.fulfill({
          status: 200,
          headers: {
            Date: new Date(now).toUTCString(),
            Age: "0",
            "Cache-Control": `public, max-age=${pollIntervalMs / 1_000}`,
          },
          json: api.data[endpoint],
        });
      });

      await use(api);
      await page.unrouteAll({ behavior: "wait" });
      expect(unexpectedRequests, "준비하지 않은 API 요청이 실제 서버로 전달되면 안 된다").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
