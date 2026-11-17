import type { Page, Route } from "@playwright/test";
import { test, expect } from "./fixtures/auth";
import { routeId } from "./fixtures/mock-data";
import { openRouteSearch, pickRoute } from "./utils/route-select";
import { NATIVE_HOME_MAP_CAPABILITIES, NATIVE_MAP_CAPABILITIES } from "../src/shared/native-map/bridge";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

const LOADING = "화면을 불러오고 있어요…";
const LOAD_FAILED = "화면을 불러오지 못했어요";

interface ScriptLog {
  requested: string[];
  after: (mark: number) => string[];
}

function isAppScript(url: URL) {
  return (
    url.origin === new URL(test.info().project.use.baseURL ?? "http://127.0.0.1").origin && url.pathname.endsWith(".js")
  );
}

function trackScripts(page: Page): ScriptLog {
  const requested: string[] = [];
  page.on("request", (request) => {
    if (request.resourceType() === "script" && isAppScript(new URL(request.url()))) requested.push(request.url());
  });
  return { requested, after: (mark) => requested.slice(mark) };
}

async function settleHome(page: Page) {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("연어버스");
  await page.waitForLoadState("networkidle");
}

type ChunkHandler = (route: Route) => Promise<void>;

async function interceptNewChunks(page: Page, known: readonly string[], handle: ChunkHandler) {
  const seen = new Set(known);
  await page.route(isAppScript, async (route) => {
    if (seen.has(route.request().url())) {
      await route.fallback();
      return;
    }
    await handle(route);
  });
}

function delayed(ms: number): ChunkHandler {
  return async (route) => {
    await new Promise((resolve) => setTimeout(resolve, ms));
    await route.fallback();
  };
}

function failing(times: number): ChunkHandler {
  let failures = 0;
  return async (route) => {
    if (failures < times) {
      failures += 1;
      await route.abort();
      return;
    }
    await route.fallback();
  };
}

function tab(page: Page, name: string) {
  return page.getByRole("navigation", { name: "주요 메뉴", exact: true }).getByRole("link", { name, exact: true });
}

test("홈 로드만으로는 다른 화면 청크를 받지 않고, 탭을 누를 때 받는다", async ({ page }) => {
  const scripts = trackScripts(page);
  await settleHome(page);
  const home = scripts.requested.length;

  await tab(page, "마이페이지").click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toBeVisible();
  expect(scripts.after(home).length).toBeGreaterThan(0);
  for (const url of scripts.after(home)) expect(scripts.requested.slice(0, home)).not.toContain(url);
});

test("검색을 열면 판정보드 청크를 미리 받고, 보드에서 돌아와 다시 들어가도 다시 받지 않는다", async ({ page }) => {
  const scripts = trackScripts(page);
  await settleHome(page);
  const home = scripts.requested.length;

  await openRouteSearch(page);
  await expect.poll(() => scripts.after(home).length).toBeGreaterThan(0);
  await pickRoute(page);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  const board = scripts.requested.length;

  await page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true }).click();
  await expect(page).toHaveURL("/");
  await openRouteSearch(page);
  await pickRoute(page);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  expect(scripts.after(board)).toEqual([]);
});

test("판정보드 청크가 늦으면 이전 화면을 유지하다가 보드로 바꾸고 홈 지도는 그대로 둔다", async ({ page }) => {
  const scripts = trackScripts(page);
  await settleHome(page);
  const map = await page.getByRole("region", { name: "지도", exact: true }).elementHandle();
  await interceptNewChunks(page, scripts.requested, delayed(2_000));

  await openRouteSearch(page);
  await pickRoute(page);
  await expect(page.getByText(LOADING, { exact: true })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  expect(await map!.evaluate((node) => node.isConnected)).toBe(true);
});

test("마이페이지 청크가 늦으면 시트 안에 불러오는 중을 보이고 탭은 바로 바뀐다", async ({ page }) => {
  const scripts = trackScripts(page);
  await settleHome(page);
  await interceptNewChunks(page, scripts.requested, delayed(2_000));

  await tab(page, "마이페이지").click();
  await expect(page.getByRole("status").filter({ hasText: LOADING })).toBeAttached();
  await expect(tab(page, "마이페이지")).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: LOADING })).toHaveCount(0);
});

test("시트 화면 청크가 실패하면 오류를 보이고 다시 시도하면 화면을 보인다", async ({ page }) => {
  const scripts = trackScripts(page);
  await settleHome(page);
  await interceptNewChunks(page, scripts.requested, failing(1));

  await tab(page, "마이페이지").click();
  await expect(page.getByText(LOAD_FAILED, { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("연어버스");
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toBeVisible();
  await expect(page.getByText(LOAD_FAILED, { exact: true })).toHaveCount(0);
});

test("판정보드 청크가 실패하면 오류를 보이고 다시 시도하면 보드를 보인다", async ({ page }) => {
  const scripts = trackScripts(page);
  await settleHome(page);
  const map = await page.getByRole("region", { name: "지도", exact: true }).elementHandle();
  await interceptNewChunks(page, scripts.requested, failing(1));

  await openRouteSearch(page);
  await routeSearchPick(page);
  await expect(page.getByRole("heading", { name: LOAD_FAILED, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  expect(await map!.evaluate((node) => node.isConnected)).toBe(true);
});

async function routeSearchPick(page: Page) {
  await page.getByRole("textbox", { name: "노선 번호", exact: true }).fill("3330");
  await page.getByRole("button", { name: /^3330번 노선, (새 노선, )?테스트 기점부터 테스트 종점 구간$/ }).click();
  await expect(page).toHaveURL(`/routes/${routeId}`);
}

test("페이지 화면 청크가 두 번 실패하면 새로고침을 권하고 새로고침하면 화면을 보인다", async ({ page }) => {
  const html = await (await page.request.get("/login")).text();
  const initialScripts = [...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(
    ([, src]) => new URL(src!, test.info().project.use.baseURL).href,
  );
  await interceptNewChunks(page, initialScripts, failing(2));

  await page.goto("/login");
  await expect(page.getByRole("heading", { name: LOAD_FAILED, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("button", { name: "새로고침", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
});

for (const path of [
  `/routes/${routeId}`,
  "/favorites",
  "/favorites/groups",
  "/mypage",
  "/login",
  "/mypage/nickname",
  "/favorites/groups/group-1",
  "/mypage/whats-new",
  "/privacy",
]) {
  test(`${path}에 직접 들어가면 불러오는 중을 지나 화면을 보인다`, async ({ page }) => {
    await page.goto(path);
    await expect(page.getByText(LOADING, { exact: true })).toHaveCount(0);
    await expect(page.getByText("앗, 문제가 생겼어요", { exact: true })).toHaveCount(0);
    await expect(page.getByText(LOAD_FAILED, { exact: true })).toHaveCount(0);
    await expect(page.getByRole("heading").first()).toBeVisible();
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
    await expect(page.locator("html")).toHaveAttribute("data-native-home-map", "true");
    await openRouteSearch(page);
    await pickRoute(page);
    await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
    expect(sdkRequests).toEqual([]);
  });
});
