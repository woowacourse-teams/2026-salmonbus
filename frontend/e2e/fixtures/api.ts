import { test as base, expect } from "@playwright/test";
import { createApiMockRouter, type ApiMockRouter } from "./api-mock-router";
import { apiPaths, createApiData, observedAt, pollIntervalMs, routeId } from "./mock-data";
import { createStationArrivalsData } from "./station-arrivals";

type Endpoint = keyof typeof apiPaths | "arrivals";
type ResponseMode = "success" | "network-error";

interface MockApi {
  route: ApiMockRouter["route"];
  data: ReturnType<typeof createApiData>;
  mode: Record<Endpoint, ResponseMode>;
  requests: Record<Endpoint, number>;
  arrivalRequests: URL[];
  cacheMaxAgeSeconds: number;
}

export const test = base.extend<{ api: MockApi }>({
  serviceWorkers: "block",
  api: [
    async ({ page }, useApi) => {
      // 페이지를 열기 전에 시계를 설치한다. 폴링 테스트만 명시적으로 시간을 진행시킨다.
      const start = new Date(observedAt).getTime();
      await page.clock.install({ time: start });
      await page.clock.pauseAt(start + 1_000);
      // 실제 SDK는 별도 검증한다. 로컬 키가 있어도 자동 테스트가 외부 지도에 의존하지 않는다.
      await page.route("https://oapi.map.naver.com/**", (route) => route.abort());

      const router = await createApiMockRouter(page);
      const api: MockApi = {
        route: router.route,
        data: createApiData(),
        mode: { routes: "success", watch: "success", geometry: "success", arrivals: "success" },
        requests: { routes: 0, watch: 0, geometry: 0, arrivals: 0 },
        arrivalRequests: [],
        cacheMaxAgeSeconds: pollIntervalMs / 1_000,
      };

      await api.route(async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname;
        if (path === "/api/auth/session") {
          await route.fulfill({
            status: 404,
            headers: { "Cache-Control": "no-store" },
            json: { code: "ENDPOINT_NOT_FOUND", message: "로그인 꺼짐", requestId: "test-auth-off" },
          });
          return;
        }
        const geometryRoute = /^\/api\/v2\/routes\/([^/]+)\/geometry$/.exec(path)?.[1];
        const endpoint: Endpoint | undefined = /^\/api\/v2\/stations\/[^/]+\/arrivals$/.test(path)
          ? "arrivals"
          : geometryRoute !== undefined
            ? "geometry"
            : (Object.keys(apiPaths) as (keyof typeof apiPaths)[]).find((key) => apiPaths[key] === path);
        if (endpoint === undefined || request.method() !== "GET") {
          await route.fallback();
          return;
        }

        api.requests[endpoint] += 1;
        if (endpoint === "arrivals") api.arrivalRequests.push(url);
        if (api.mode[endpoint] === "network-error") {
          await route.abort("failed");
          return;
        }

        // Date 헤더도 브라우저의 제어된 시간과 맞춰 서버 기준 시계가 어긋나지 않게 한다.
        // 테스트가 page.clock을 직접 움직여서 시각은 fixture가 아니라 브라우저에 묻는다.
        const now = await page
          .evaluate(() => Date.now())
          .catch((error: unknown) => {
            // 요청이 걸린 사이 새 문서로 이동했다면 버려진 문서의 요청이라 응답하지 않는다.
            if (isAbandonedDocumentError(error)) return null;
            throw error;
          });
        if (now === null) {
          await route.abort();
          return;
        }
        await route.fulfill({
          status: 200,
          headers: {
            Date: new Date(now).toUTCString(),
            Age: "0",
            "Cache-Control":
              endpoint === "arrivals" && api.data.arrivals?.status === "UNAVAILABLE"
                ? "no-store"
                : `public, max-age=${endpoint === "arrivals" ? 5 : api.cacheMaxAgeSeconds}`,
          },
          json:
            endpoint === "arrivals"
              ? (api.data.arrivals ?? createStationArrivalsData(url, now))
              : endpoint === "geometry" && geometryRoute !== undefined && geometryRoute !== routeId
                ? { ...api.data.geometry, routeId: decodeURIComponent(geometryRoute) }
                : api.data[endpoint],
        });
      });

      try {
        await useApi(api);
      } finally {
        await router.dispose();
      }
    },
    { auto: true },
  ],
});

export function isAbandonedDocumentError(error: unknown) {
  return error instanceof Error && error.message.includes("Execution context was destroyed");
}

export { expect };
