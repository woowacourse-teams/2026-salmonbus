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

const FAVORITES_CHUNK = "등록된 즐겨찾기가 없어요";
const ACCOUNT_CHUNK = "카카오로 계속하기";

async function interceptChunk(page: Page, marker: string, handle: ChunkHandler) {
  await page.route(
    (url) => isAppAsset(url) && url.pathname.endsWith(".js"),
    async (route) => {
      const response = await route.fetch();
      const body = await response.text();
      if (!body.includes(marker)) {
        await route.fulfill({ response, body });
        return;
      }
      await handle(route);
    },
  );
}

async function openHome(page: Page) {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("연어버스");
}

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

async function settlePrefetchedHome(page: Page) {
  const initial = await initialScriptsOf(page, "/");
  const prefetched = new Set<string>();
  page.on("response", async (response) => {
    const url = new URL(response.url());
    if (!isAppAsset(url) || !url.pathname.endsWith(".js")) return;
    const body = await response.text().catch(() => "");
    for (const marker of [FAVORITES_CHUNK, ACCOUNT_CHUNK]) if (body.includes(marker)) prefetched.add(marker);
  });
  await settleHome(page);
  await page.clock.runFor(1_000);
  await expect.poll(() => prefetched.size).toBe(2);
  await page.waitForLoadState("networkidle");
  return initial;
}

test("홈 첫 스크립트에는 다른 화면 코드가 없고, 쉬는 시간에 탭 화면 청크를 미리 받아 탭을 누를 때 다시 받지 않는다", async ({
  page,
}) => {
  await watchFallback(page);
  const assets = trackAssets(page);
  const initial = await settlePrefetchedHome(page);
  for (const src of initial) {
    const body = await (await page.request.get(src)).text();
    expect(body).not.toContain(FAVORITES_CHUNK);
    expect(body).not.toContain(ACCOUNT_CHUNK);
  }
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

test("검색에서 노선을 고르면 새 청크 없이 판정보드로 바뀌고 홈 지도는 그대로 둔다", async ({ page }) => {
  await watchFallback(page);
  const assets = trackAssets(page);
  await settlePrefetchedHome(page);
  const home = assets.requested.length;
  const map = await homeMap(page).elementHandle();

  await openRouteSearch(page);
  await expect(routeSearchInput(page)).toBeFocused();
  await pickRoute(page);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  expect(assets.after(home).filter((url) => url.endsWith(".js"))).toEqual([]);
  expect(await fallbackSeen(page)).toBe(false);
  expect(await map!.evaluate((node) => node.isConnected)).toBe(true);
});

const sheetTabs: {
  name: string;
  path: string;
  marker: string;
  shows: (page: Page) => ReturnType<Page["getByRole"]>;
}[] = [
  {
    name: "즐겨찾기",
    path: "/favorites",
    marker: FAVORITES_CHUNK,
    shows: (page) => page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true }),
  },
  { name: "마이페이지", path: "/mypage", marker: ACCOUNT_CHUNK, shows: guestLogin },
];

for (const { name, path, marker, shows } of sheetTabs) {
  test(`${name} 탭 화면 청크가 늦으면 홈 화면을 유지하다가 바꾸고 불러오는 중 화면을 보이지 않는다`, async ({
    page,
  }) => {
    await watchFallback(page);
    const chunks = gate();
    await interceptChunk(page, marker, chunks.handle);
    await openHome(page);
    const map = await homeMap(page).elementHandle();

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
  const chunks = gate();
  await interceptChunk(page, ACCOUNT_CHUNK, chunks.handle);
  await openHome(page);
  await tab(page, "즐겨찾기").click();
  const card = page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true });
  await expect(card).toBeVisible();

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
  await interceptChunk(page, ACCOUNT_CHUNK, failing(1));
  await openHome(page);

  await tab(page, "마이페이지").click();
  const sheet = page.getByRole("region", { name: "마이페이지", exact: true });
  await expect(sheet.getByText(LOAD_FAILED, { exact: true })).toBeVisible();
  await expect(homeMap(page)).toBeVisible();
  await sheet.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(guestLogin(page)).toBeVisible();
  await expect(page.getByText(LOAD_FAILED, { exact: true })).toHaveCount(0);
  expect(await fallbackSeen(page)).toBe(false);
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
  { path: "/mypage", shows: guestLogin },
  { path: "/login", shows: (page) => page.getByRole("button", { name: "카카오로 계속하기", exact: true }) },
  { path: "/mypage/nickname", shows: (page) => page.getByRole("heading", { name: "닉네임 수정", exact: true }) },
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

const loadOrderEntries = directEntries.filter(({ path }) => ["/favorites", "/mypage", "/login"].includes(path));

for (const { path, shows } of loadOrderEntries) {
  test(`${path} 직접 진입에서 화면 청크는 window load 뒤에 붙여 load 이벤트를 미루지 않는다`, async ({ page }) => {
    const initial = await initialScriptsOf(page, path);
    await page.addInitScript(() => {
      const order: string[] = [];
      Object.assign(window, { assetOrder: order });
      window.addEventListener("load", () => order.push("load"));
      new MutationObserver((records) => {
        for (const record of records) {
          for (const node of record.addedNodes) {
            if (node instanceof HTMLScriptElement && node.src) order.push(node.src);
            if (node instanceof HTMLLinkElement && node.rel === "stylesheet") order.push(node.href);
          }
        }
      }).observe(document, { subtree: true, childList: true });
    });
    await page.goto(path);
    await expect(shows(page)).toBeVisible();
    const order = await page.evaluate(() => (window as unknown as { assetOrder: string[] }).assetOrder);
    const loadAt = order.indexOf("load");
    const chunks = order
      .map((entry, index) => ({ entry, index }))
      .filter(({ entry }) => entry !== "load" && entry.endsWith(".js") && !initial.includes(entry));
    expect(loadAt).toBeGreaterThanOrEqual(0);
    expect(chunks.length).toBeGreaterThan(0);
    for (const { index } of chunks) expect(index).toBeGreaterThan(loadAt);
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

  test("판정보드에 들어가는 동안 웹 지도 SDK를 요청하지 않는다", async ({ page }) => {
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
