import type { Page } from "@playwright/test";
import { test as mapTest, expect } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";
import { NATIVE_HOME_MAP_CAPABILITIES, NATIVE_MAP_CAPABILITIES } from "../src/shared/native-map/bridge";
import { STARTUP_MARKS } from "../src/shared/startup/startupMarks";

const test = mapTest.extend<{ appCapabilities: readonly string[] | null; mapRequests: string[] }>({
  appCapabilities: [null, { option: true }],
  mapRequests: async ({ page }, provideRequests) => {
    const requests: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).hostname === "oapi.map.naver.com") requests.push(request.url());
    });
    await provideRequests(requests);
  },
});

test.use({ viewport: { width: 390, height: 844 } });

type StartupMarkWindow = { __startupMarkCalls: string[] };

test.beforeEach(async ({ page, appCapabilities }) => {
  // page.clock이 바꿔 둔 performance는 mark를 남기지 않고 getEntriesByType이 항상 빈 배열이라, mark 호출을 직접 모은다.
  await page.addInitScript(() => {
    const calls: string[] = [];
    const mark = performance.mark.bind(performance);
    performance.mark = (name, options) => {
      calls.push(name);
      return mark(name, options);
    };
    (window as unknown as StartupMarkWindow).__startupMarkCalls = calls;
  });
  if (appCapabilities === null) return;
  await page.addInitScript((capabilities) => {
    window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
    window.ReactNativeWebView = {
      postMessage(raw: string) {
        const message = JSON.parse(raw) as { type: string; payload: { sessionId: string; revision: number } };
        const mount = message.type === "map.mount" || message.type === "map.mountHome";
        if (!mount && message.type !== "map.layout") return;
        const { sessionId, revision } = message.payload;
        queueMicrotask(() =>
          window.dispatchEvent(
            new CustomEvent("salmon:bridge", {
              detail: {
                v: 1,
                type: mount ? "map.ready" : "map.ack",
                payload: mount ? { sessionId, revision } : { sessionId, revision, appliedRevision: revision },
              },
            }),
          ),
        );
      },
    };
  }, appCapabilities);
});

async function startupMarks(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    (window as unknown as StartupMarkWindow).__startupMarkCalls.filter((name) => name.startsWith("salmon:")),
  );
}

const webMarks = [STARTUP_MARKS.webEntry, STARTUP_MARKS.webFirstCommit, STARTUP_MARKS.webShellCommit];

test.describe("브라우저", () => {
  test("웹 시작 마커를 정한 순서로 한 번씩 찍는다", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("main").first()).toBeVisible();
    await expect.poll(() => startupMarks(page)).toEqual(webMarks);
  });
});

test.describe("판정보드 지도만 지원하는 구버전 앱", () => {
  test.use({ appCapabilities: NATIVE_MAP_CAPABILITIES });

  test("홈과 판정보드 모두 웹 마커만 찍는다", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("main").first()).toBeVisible();
    await expect.poll(() => startupMarks(page)).toEqual(webMarks);
    await page.goto(`/routes/${routeId}`);
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
    await expect.poll(() => startupMarks(page)).toEqual(webMarks);
  });
});

test.describe("홈 지도까지 지원하는 새 iOS 앱", () => {
  test.use({ appCapabilities: [...NATIVE_MAP_CAPABILITIES, ...NATIVE_HOME_MAP_CAPABILITIES] });

  test("native 지도 마커와 투명화를 찍고 웹 지도 SDK는 요청하지 않는다", async ({ page, mapRequests }) => {
    await page.goto("/");
    await expect(page.locator("html")).toHaveAttribute("data-native-home-map", "true");
    await expect
      .poll(async () => (await startupMarks(page)).slice(0, 6))
      .toEqual([
        ...webMarks,
        STARTUP_MARKS.mapNativeMount,
        STARTUP_MARKS.mapNativeReady,
        STARTUP_MARKS.mapWebTransparent,
      ]);
    expect(mapRequests).toEqual([]);
    await expect(page.locator('script[src*="oapi.map.naver.com"]')).toHaveCount(0);
  });

  test("판정보드 직접 진입에서는 지도 마커를 찍지 않고 웹 지도 SDK도 요청하지 않는다", async ({
    page,
    mapRequests,
  }) => {
    await page.goto(`/routes/${routeId}`);
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
    await expect.poll(() => startupMarks(page)).toEqual(webMarks);
    expect(mapRequests).toEqual([]);
    await expect(page.locator('script[src*="oapi.map.naver.com"]')).toHaveCount(0);
  });
});
