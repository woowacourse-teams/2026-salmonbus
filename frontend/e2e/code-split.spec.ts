import type { Page, Route } from "@playwright/test";
import { test, expect } from "./fixtures/auth";
import { routeId } from "./fixtures/mock-data";
import { openRouteSearch, pickRoute, routeSearchInput } from "./utils/route-select";
import { NATIVE_HOME_MAP_CAPABILITIES, NATIVE_MAP_CAPABILITIES } from "../src/shared/native-map/bridge";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

const LOADING = "화면을 불러오고 있어요…";
const LOAD_FAILED = "화면을 불러오지 못했어요";
const APP_ERROR = "앗, 문제가 생겼어요";

interface AssetLog {
  requested: string[];
  after: (mark: number) => string[];
}

function isAppAsset(url: URL) {
  return (
    url.origin === new URL(test.info().project.use.baseURL ?? "http://127.0.0.1").origin &&
    /\.(js|css)$/.test(url.pathname)
  );
}

function trackAssets(page: Page): AssetLog {
  const requested: string[] = [];
  page.on("request", (request) => {
    if (isAppAsset(new URL(request.url()))) requested.push(request.url());
  });
  return { requested, after: (mark) => requested.slice(mark) };
}

function homeMap(page: Page) {
  return page.getByRole("region", { name: "지도", exact: true });
}

async function settleHome(page: Page) {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("연어버스");
  await page.waitForLoadState("networkidle");
}

type ChunkHandler = (route: Route) => Promise<void>;

async function interceptNewScripts(page: Page, known: readonly string[], handle: ChunkHandler) {
  const seen = new Set(known);
  await page.route(
    (url) => isAppAsset(url) && url.pathname.endsWith(".js"),
    async (route) => {
      if (seen.has(route.request().url())) {
        await route.fallback();
        return;
      }
      await handle(route);
    },
  );
}

interface ChunkGate {
  handle: ChunkHandler;
  held: () => number;
  release: () => void;
}

function gate(): ChunkGate {
  let open = () => undefined as void;
  const released = new Promise<void>((resolve) => {
    open = resolve;
  });
  let held = 0;
  return {
    handle: async (route) => {
      held += 1;
      await released;
      await route.fallback();
    },
    held: () => held,
    release: () => open(),
  };
}

async function watchFallback(page: Page) {
  await page.addInitScript((text) => {
    const marker = window as unknown as { fallbackSeen?: boolean };
    marker.fallbackSeen = false;
    new MutationObserver(() => {
      if (document.body?.textContent?.includes(text)) marker.fallbackSeen = true;
    }).observe(document, { subtree: true, childList: true, characterData: true });
  }, LOADING);
}

function fallbackSeen(page: Page) {
  return page.evaluate(() => (window as unknown as { fallbackSeen?: boolean }).fallbackSeen);
}

function failing(times: number): ChunkHandler {
  const failed = new Map<string, number>();
  return async (route) => {
    const url = route.request().url();
    const count = failed.get(url) ?? 0;
    if (count < times) {
      failed.set(url, count + 1);
      await route.abort();
      return;
    }
    await route.fallback();
  };
}

async function initialScriptsOf(page: Page, path: string) {
  const html = await (await page.request.get(path)).text();
  return [...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(
    ([, src]) => new URL(src!, test.info().project.use.baseURL).href,
  );
}

function tab(page: Page, name: string) {
  return page.getByRole("navigation", { name: "주요 메뉴", exact: true }).getByRole("link", { name, exact: true });
}

function guestLogin(page: Page) {
  return page.getByRole("link", { name: "로그인 / 회원가입", exact: true });
}

test("홈은 다른 화면 청크 없이 그리고, 쉬는 시간에 탭 화면 청크를 미리 받아 탭을 누를 때 다시 받지 않는다", async ({
  page,
}) => {
  await watchFallback(page);
  const assets = trackAssets(page);
  await settleHome(page);
  const home = assets.requested.length;
  await page.waitForTimeout(2_000);
  expect(assets.after(home)).toEqual([]);

  await page.clock.runFor(1_000);
  await expect.poll(() => assets.after(home).filter((url) => url.endsWith(".js")).length).toBeGreaterThan(1);
  await page.waitForLoadState("networkidle");
  const prefetched = assets.requested.length;

  await tab(page, "마이페이지").click();
  await expect(page).toHaveURL("/mypage");
  await expect(guestLogin(page)).toBeVisible();
  await tab(page, "즐겨찾기").click();
  await expect(page).toHaveURL("/favorites");
  await expect(page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true })).toBeVisible();
  expect(assets.after(prefetched)).toEqual([]);
  expect(await fallbackSeen(page)).toBe(false);
});

test("검색을 열면 입력창에 포커스가 가고 판정보드 청크를 미리 받으며, 다시 들어가도 다시 받지 않는다", async ({
  page,
}) => {
  const assets = trackAssets(page);
  await settleHome(page);
  const home = assets.requested.length;

  await openRouteSearch(page);
  await expect(routeSearchInput(page)).toBeFocused();
  await expect.poll(() => assets.after(home).length).toBeGreaterThan(0);
  await pickRoute(page);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  const board = assets.requested.length;

  await page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true }).click();
  await expect(page).toHaveURL("/");
  await openRouteSearch(page);
  await pickRoute(page);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  expect(assets.after(board)).toEqual([]);
});

test("판정보드 청크가 늦으면 검색 화면을 유지하다가 보드로 바꾸고 홈 지도는 그대로 둔다", async ({ page }) => {
  await watchFallback(page);
  const assets = trackAssets(page);
  await settleHome(page);
  const map = await homeMap(page).elementHandle();
  const chunks = gate();
  await interceptNewScripts(page, assets.requested, chunks.handle);

  await openRouteSearch(page);
  await routeSearchInput(page).fill("3330");
  await page.getByRole("button", { name: /^3330번 노선, (새 노선, )?테스트 기점부터 테스트 종점 구간$/ }).click();
  await expect.poll(chunks.held).toBeGreaterThan(0);
  await expect(routeSearchInput(page)).toBeVisible();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toHaveCount(0);
  await expect(page.getByText(LOADING, { exact: true })).toHaveCount(0);

  chunks.release();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  await expect(page).toHaveURL(`/routes/${routeId}`);
  expect(await fallbackSeen(page)).toBe(false);
  expect(await map!.evaluate((node) => node.isConnected)).toBe(true);
});

const sheetTabs: { name: string; path: string; shows: (page: Page) => ReturnType<Page["getByRole"]> }[] = [
  {
    name: "즐겨찾기",
    path: "/favorites",
    shows: (page) => page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true }),
  },
  { name: "마이페이지", path: "/mypage", shows: guestLogin },
];

for (const { name, path, shows } of sheetTabs) {
  test(`${name} 탭 화면 청크가 늦으면 홈 화면을 유지하다가 바꾸고 불러오는 중 화면을 보이지 않는다`, async ({
    page,
  }) => {
    await watchFallback(page);
    const assets = trackAssets(page);
    await settleHome(page);
    const map = await homeMap(page).elementHandle();
    const chunks = gate();
    await interceptNewScripts(page, assets.requested, chunks.handle);

    await tab(page, name).click();
    await expect.poll(chunks.held).toBeGreaterThan(0);
    await expect(page).toHaveURL(path);
    await expect(tab(page, "홈")).toHaveAttribute("aria-current", "page");
    await expect(shows(page)).toHaveCount(0);
    await expect(page.getByText(LOADING, { exact: true })).toHaveCount(0);
    expect(await map!.evaluate((node) => node.isConnected)).toBe(true);

    chunks.release();
    await expect(shows(page)).toBeVisible();
    await expect(tab(page, name)).toHaveAttribute("aria-current", "page");
    expect(await fallbackSeen(page)).toBe(false);
    expect(await map!.evaluate((node) => node.isConnected)).toBe(true);
  });
}

test("전체 화면 청크가 늦으면 즐겨찾기 화면을 유지하다가 로그인 화면으로 바꾼다", async ({ page }) => {
  await watchFallback(page);
  const assets = trackAssets(page);
  await settleHome(page);
  await tab(page, "즐겨찾기").click();
  const card = page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true });
  await expect(card).toBeVisible();
  await page.waitForLoadState("networkidle");
  const chunks = gate();
  await interceptNewScripts(page, assets.requested, chunks.handle);

  await card.getByRole("link", { name: "로그인 / 회원가입", exact: true }).click();
  await expect.poll(chunks.held).toBeGreaterThan(0);
  await expect(card).toBeVisible();
  await expect(page.getByText(LOADING, { exact: true })).toHaveCount(0);

  chunks.release();
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
  expect(await fallbackSeen(page)).toBe(false);
});

test("시트 화면 청크가 실패하면 시트 안에 오류를 보이고 다시 시도하면 화면을 보인다", async ({ page }) => {
  await watchFallback(page);
  const assets = trackAssets(page);
  await settleHome(page);
  await interceptNewScripts(page, assets.requested, failing(1));

  await tab(page, "마이페이지").click();
  await expect(page.getByText(LOAD_FAILED, { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("연어버스");
  await expect(homeMap(page)).toBeVisible();
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(guestLogin(page)).toBeVisible();
  await expect(page.getByText(LOAD_FAILED, { exact: true })).toHaveCount(0);
  expect(await fallbackSeen(page)).toBe(false);
});

test("판정보드 청크가 실패하면 오류를 보이고 다시 시도하면 보드를 보인다", async ({ page }) => {
  const assets = trackAssets(page);
  await settleHome(page);
  const map = await homeMap(page).elementHandle();
  await interceptNewScripts(page, assets.requested, failing(1));

  await openRouteSearch(page);
  await pickRoute(page);
  await expect(page.getByRole("heading", { name: LOAD_FAILED, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  expect(await map!.evaluate((node) => node.isConnected)).toBe(true);
});

test("페이지 화면 청크가 한 번 실패하면 다시 시도로 화면을 보인다", async ({ page }) => {
  await interceptNewScripts(page, await initialScriptsOf(page, "/login"), failing(1));

  await page.goto("/login");
  await expect(page.getByRole("heading", { name: LOAD_FAILED, exact: true })).toBeVisible();
  await expect(page.getByText(APP_ERROR, { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
});

test("페이지 화면 청크가 두 번 실패하면 새로고침을 권하고 새로고침하면 화면을 보인다", async ({ page }) => {
  await interceptNewScripts(page, await initialScriptsOf(page, "/login"), failing(2));

  await page.goto("/login");
  await expect(page.getByRole("heading", { name: LOAD_FAILED, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("button", { name: "새로고침", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
});

const directEntries: { path: string; url?: string; shows: (page: Page) => ReturnType<Page["getByRole"]> }[] = [
  {
    path: `/routes/${routeId}`,
    shows: (page) => page.getByRole("heading", { name: "3330", exact: true }),
  },
  { path: "/favorites", shows: (page) => page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true }) },
  {
    path: "/favorites/groups",
    shows: (page) => page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true }),
  },
  { path: "/mypage", shows: guestLogin },
  { path: "/login", shows: (page) => page.getByRole("button", { name: "카카오로 계속하기", exact: true }) },
  { path: "/mypage/nickname", shows: (page) => page.getByRole("heading", { name: "닉네임 수정", exact: true }) },
  { path: "/favorites/groups/group-1", shows: (page) => page.getByRole("heading", { name: "그룹 관리", exact: true }) },
  {
    path: "/mypage/groups/group-1",
    url: "/favorites/groups/group-1",
    shows: (page) => page.getByRole("heading", { name: "그룹 관리", exact: true }),
  },
  { path: "/mypage/whats-new", shows: (page) => page.getByRole("heading", { name: "새 소식", exact: true }) },
];

for (const { path, url, shows } of directEntries) {
  test(`${path}에 직접 들어가면 불러오는 중 화면 없이 그 화면을 보인다`, async ({ page }) => {
    await watchFallback(page);
    await page.goto(path);
    await expect(shows(page)).toBeVisible();
    await expect(page).toHaveURL(url ?? path);
    expect(await fallbackSeen(page)).toBe(false);
    await expect(page.getByText(APP_ERROR, { exact: true })).toHaveCount(0);
    await expect(page.getByText(LOAD_FAILED, { exact: true })).toHaveCount(0);
  });
}

const loadOrderEntries = directEntries.filter(({ path }) =>
  [`/routes/${routeId}`, "/favorites", "/mypage", "/login"].includes(path),
);

for (const { path, shows } of loadOrderEntries) {
  test(`${path} 직접 진입에서 화면 청크는 window load가 끝난 뒤에 받아 load 이벤트를 미루지 않는다`, async ({
    page,
  }) => {
    const initial = await initialScriptsOf(page, path);
    await page.goto(path);
    await expect(shows(page)).toBeVisible();
    const timing = await page.evaluate((initialScripts) => {
      const navigation = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming;
      const chunks = performance
        .getEntriesByType("resource")
        .filter((entry) => /\.js$/.test(new URL(entry.name).pathname) && new URL(entry.name).origin === location.origin)
        .filter((entry) => !initialScripts.includes(entry.name))
        .map((entry) => entry.startTime);
      return { loadEventStart: navigation.loadEventStart, chunks };
    }, initial);
    expect(timing.loadEventStart).toBeGreaterThan(0);
    expect(timing.chunks.length).toBeGreaterThan(0);
    for (const start of timing.chunks) expect(start).toBeGreaterThanOrEqual(timing.loadEventStart);
  });
}

test.describe("홈 native 지도가 있는 앱", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(
      (capabilities) => {
        window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
        window.ReactNativeWebView = { postMessage() {} };
      },
      [...NATIVE_MAP_CAPABILITIES, ...NATIVE_HOME_MAP_CAPABILITIES],
    );
  });

  test("판정보드 청크를 받고 보드에 들어가는 동안 웹 지도 SDK를 요청하지 않는다", async ({ page }) => {
    const sdkRequests: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).hostname === "oapi.map.naver.com") sdkRequests.push(request.url());
    });
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("연어버스");
    await openRouteSearch(page);
    await pickRoute(page);
    await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
    expect(sdkRequests).toEqual([]);
  });
});
