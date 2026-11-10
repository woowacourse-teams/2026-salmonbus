import { mergeTests } from "@playwright/test";
import { test as favoritesTest, expect, storedFavorite } from "./fixtures/favorites";
import { test as mapTest } from "./fixtures/naver-map";
import { apiPaths, routeId } from "./fixtures/mock-data";
import { captureEnabled } from "./utils/capture";
import { activeRouteColor, inactiveRouteColor } from "../src/shared/map/routeColors";

const test = mergeTests(mapTest, favoritesTest);
test.use({ viewport: { width: 425, height: 844 }, reducedMotion: "no-preference" });

test("즐겨찾기 지도에서 판정보드를 먼저 열고 노선 응답 뒤 저장한 정류장을 표시한다", async ({
  page,
  favorites,
  api,
  naverMap,
  browserName,
}) => {
  const stop = api.data.watch.stops.find((stop) => stop.direction === "DOWN" && stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  let releaseGeometry!: () => void;
  const geometryPending = new Promise<void>((resolve) => {
    releaseGeometry = resolve;
  });
  await api.route(async (route) => {
    if (new URL(route.request().url()).pathname === apiPaths.geometry) await geometryPending;
    await route.fallback();
  });
  await page.goto("/favorites");
  const map = page.getByTestId("naver-map-test-double");
  await expect(map).toBeVisible();
  const originalMap = await map.elementHandle();
  await naverMap.setZoom(13);
  const camera = await naverMap.snapshot();
  try {
    await page.getByRole("tabpanel").getByRole("list").first().getByRole("link").click();
    await expect(page).toHaveURL(`/routes/${routeId}?direction=DOWN&stopId=${stop.stopId}`);
    await expect(page.getByRole("region", { name: "판정보드", exact: true })).toBeVisible();
    await expect(page.getByText("지도 정보를 준비 중이에요", { exact: true })).toHaveCount(0);
    await expect(map.getByTestId("naver-polyline")).toHaveCount(0);
    expect(await naverMap.snapshot()).toEqual(camera);
    expect(await originalMap?.evaluate((element) => element.isConnected)).toBe(true);
  } finally {
    releaseGeometry();
  }
  const point = api.data.geometry.busStops.find(
    (item) => item.stationId === stop.stopId && item.direction === stop.direction,
  )!.point!;
  await expect(map.getByTestId("naver-polyline")).toHaveCount(2);
  expect(await originalMap?.evaluate((element) => element.isConnected)).toBe(true);
  await page.clock.runFor(64);
  await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeVisible();
  await page.clock.runFor(140);
  const middle = await naverMap.snapshot();
  expect(middle.zoom).toBeGreaterThan(13);
  expect(middle.zoom).toBeLessThan(15);
  await page.clock.runFor(600);
  expect((await naverMap.snapshot()).zoom).toBe(15);
  const pin = page.getByRole("button", { name: `${stop.name} 테스트 기점 방면 정류장 지도에서 선택`, exact: true });
  await expect(pin).toHaveAttribute("aria-pressed", "true");
  await expect(pin).toBeInViewport();
  if (captureEnabled && browserName === "chromium")
    await page.screenshot({ path: test.info().outputPath("favorite-map.png") });
  const focused = await naverMap.snapshot();
  expect(focused.latitude).toBeCloseTo(point.y, 6);
  expect(focused.longitude).toBeCloseTo(point.x, 6);
  await page.getByRole("slider", { name: "판정보드 높이" }).press("ArrowDown");
  await page.clock.runFor(600);
  expect(await naverMap.snapshot()).toEqual(focused);
  await page.goBack();
  await expect(page).toHaveURL("/favorites");
  expect(await originalMap?.evaluate((element) => element.isConnected)).toBe(true);
  await expect(map.locator(`polyline[stroke="${activeRouteColor}"]`)).toHaveCount(1);
  await expect(map.locator(`polyline[stroke="${inactiveRouteColor}"]`)).toHaveCount(1);
  expect(await naverMap.snapshot()).toEqual(camera);
});

test("즐겨찾기 지도는 중복 저장한 노선을 한 번만 그리며 차량 위치를 조회하지 않는다", async ({
  page,
  favorites,
  api,
}) => {
  const target = { routeId, stopId: "1008", direction: "UP" } as const;
  favorites.groups[0]!.favorites = [storedFavorite(target)];
  favorites.groups.push({
    id: "10000000-0000-4000-8000-000000000003",
    name: "통학",
    favorites: [storedFavorite(target, 4)],
  });
  api.cacheMaxAgeSeconds = 5;
  await page.goto("/favorites");
  const map = page.getByTestId("naver-map-test-double");
  const lines = map.locator('polyline[stroke-width="6"]');
  const buses = map.locator("[data-map-vehicle-key]");
  await expect(lines).toHaveCount(2);
  await expect(map.locator(`polyline[stroke="${activeRouteColor}"]`)).toHaveCount(1);
  await expect(map.locator(`polyline[stroke="${inactiveRouteColor}"]`)).toHaveCount(1);
  await page.clock.runFor(10_000);
  await expect(buses).toHaveCount(0);
  expect(api.requests.watch).toBe(0);
  expect(api.requests.geometry).toBe(1);
  await page.getByRole("navigation", { name: "주요 메뉴" }).getByRole("link", { name: "홈", exact: true }).click();
  await expect(lines).toHaveCount(0);
});

test("그룹 응답을 기다리는 동안 이전 카드와 노선을 함께 유지하고 돌아와도 선택을 유지한다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.watch.stops.find((stop) => stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  const groupId = "10000000-0000-4000-8000-000000000003";
  favorites.groups.push({ id: groupId, name: "통학", favorites: [] });
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await api.route(async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/me/favorites" && url.searchParams.get("groupId") === groupId) await pending;
    await route.fallback();
  });
  await page.goto("/favorites");
  const panel = page.getByRole("tabpanel");
  const lines = page.getByTestId("naver-map-test-double").getByTestId("naver-polyline");
  await expect(panel.getByText(stop.name, { exact: true })).toBeVisible();
  await expect(lines).toHaveCount(2);
  const group = page.getByRole("tab", { name: "통학", exact: true });
  try {
    await group.click();
    await expect(page.getByText("통학 그룹의 즐겨찾기를 불러오고 있어요…")).toBeVisible();
    await expect(panel.getByText(stop.name, { exact: true })).toBeVisible();
    await expect(lines).toHaveCount(2);
  } finally {
    release();
  }
  await expect(panel.getByText("등록된 즐겨찾기가 없어요", { exact: true })).toBeVisible();
  await expect(lines).toHaveCount(0);

  const navigation = page.getByRole("navigation", { name: "주요 메뉴" });
  await navigation.getByRole("link", { name: "홈", exact: true }).click();
  await navigation.getByRole("link", { name: "즐겨찾기", exact: true }).click();
  await expect(group).toHaveAttribute("aria-selected", "true");
  await panel.getByRole("link", { name: "즐겨찾기 추가하기" }).click();
  await expect(page).toHaveURL("/search");
  await page.goBack();
  await expect(group).toHaveAttribute("aria-selected", "true");
});

for (const reason of ["조회 실패", "표시 제한"] as const) {
  test(`즐겨찾기 노선 형상 ${reason} 시 지도 안내와 카드를 보여준다`, async ({ page, favorites, api }) => {
    const stop = api.data.watch.stops.find((stop) => stop.boardingAllowed)!;
    favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
    if (reason === "조회 실패") {
      api.mode.geometry = "network-error";
    } else {
      await api.route(async (route) => {
        if (new URL(route.request().url()).pathname === apiPaths.geometry) {
          await route.fulfill({ json: { ...api.data.geometry, status: "UNAVAILABLE", reason: "DISPLAY_WITHHELD" } });
        } else {
          await route.fallback();
        }
      });
    }
    await page.goto("/favorites");
    await expect(page.getByRole("tabpanel").getByText(stop.name, { exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "지도", exact: true })).toContainText(
      "지도에 표시할 수 없는 즐겨찾기 노선이 있어요.",
    );
    await expect(page.getByTestId("naver-polyline")).toHaveCount(0);
  });
}
