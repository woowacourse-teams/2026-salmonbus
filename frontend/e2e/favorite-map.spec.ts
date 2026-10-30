import { mergeTests } from "@playwright/test";
import { test as favoritesTest, expect, storedFavorite } from "./fixtures/favorites";
import { test as mapTest } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";
import { captureEnabled } from "./utils/capture";

const test = mergeTests(mapTest, favoritesTest);
test.use({ viewport: { width: 425, height: 844 }, holdMapInitialization: true, reducedMotion: "no-preference" });

test("홈 즐겨찾기는 선택 정류장 중심에서 준비 후 부드럽게 확대한다 (no-preference)", async ({
  page,
  favorites,
  api,
  naverMap,
  browserName,
}) => {
  const stop = api.data.watch.stops.find((stop) => stop.direction === "DOWN" && stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  await page.goto("/");
  await page.getByRole("tabpanel").getByRole("link").click();
  await expect(page).toHaveURL(`/routes/${routeId}?direction=DOWN&stopId=${stop.stopId}`);
  await expect(page.getByTestId("naver-map-test-double")).toHaveCount(1);
  await page.clock.runFor(64);
  await expect(page.getByTestId("route-map")).toHaveAttribute("data-overlays-ready", "false");
  const point = api.data.geometry.busStops.find(
    (item) => item.stationId === stop.stopId && item.direction === stop.direction,
  )!.point!;
  expect((await naverMap.snapshot()).zoom).toBe(13);
  await naverMap.initialize();
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
});
