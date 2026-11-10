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
