import type { Locator, Page } from "@playwright/test";
import { apiPaths, directionLabels, pollIntervalMs, routeId, targetStopNames } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/naver-map";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce", screenshot: "only-on-failure" });

function sheet(page: Page) {
  return page.getByRole("region", { name: "판정보드", exact: true });
}
function list(page: Page) {
  return page.getByRole("region", { name: "판정보드 정류장 목록", exact: true });
}
function map(page: Page) {
  return page.getByRole("region", { name: "노선 지도", exact: true });
}
function handle(page: Page) {
  return page.getByRole("slider", { name: "판정보드 높이" });
}
function tab(page: Page, direction: "UP" | "DOWN") {
  return page.getByRole("group", { name: "운행 방향 선택" }).getByRole("button", {
    name: direction === "UP" ? "테스트 종점 방면" : "테스트 기점 방면",
    exact: true,
  });
}

async function openMap(page: Page) {
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  await expect(page.getByTestId("naver-map-test-double")).toHaveCount(1);
  await page.clock.runFor(48);
  await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
}

async function height(locator: Locator) {
  return locator.evaluate((element) => element.getBoundingClientRect().height);
}

async function expectSheetAndMapHeights(page: Page, sheetHeight: number, mapHeight: number) {
  await expect
    .poll(async () => {
      // resize 이벤트와 애니메이션 뒤의 ResizeObserver 갱신이 반영될 프레임을 진행한다.
      await page.clock.runFor(32);
      const [actualSheetHeight, actualMapHeight] = await Promise.all([height(sheet(page)), height(map(page))]);
      return Math.max(Math.abs(actualSheetHeight - sheetHeight), Math.abs(actualMapHeight - mapHeight));
    })
    .toBeLessThanOrEqual(1);
}

async function expectStopFocusedInList(page: Page, sequence: number) {
  const stop = list(page).locator(`li[data-stop-sequence='${sequence}'] button[aria-expanded]`);
  await expect
    .poll(
      async () => {
        await page.clock.runFor(32);
        return stop.evaluate((element) => {
          const container = element.closest('[aria-label="판정보드 정류장 목록"]');
          if (container === null) return false;
          const row = element.getBoundingClientRect();
          const viewport = container.getBoundingClientRect();
          return document.activeElement === element && row.top >= viewport.top - 1 && row.bottom <= viewport.bottom + 1;
        });
      },
      { intervals: [16] },
    )
    .toBe(true);
}

async function vehicleOffsetFromMapCenter(marker: Locator) {
  return marker.evaluate((element) => {
    const region = element.closest('[aria-label="노선 지도"]');
    if (region === null) throw new Error("지도 영역이 없습니다");
    const bounds = region.getBoundingClientRect();
    const pin = element.getBoundingClientRect();
    return Math.hypot(
      pin.left + pin.width / 2 - bounds.left - bounds.width / 2,
      pin.bottom - bounds.top - bounds.height / 2,
    );
  });
}

async function expectVehicleCenteredOnMap(
  page: Page,
  marker: Locator,
  naverMap: { snapshot: () => Promise<{ zoom: number }> },
  zoom = 16,
) {
  await expect
    .poll(
      async () => {
        await page.clock.runFor(16);
        if ((await naverMap.snapshot()).zoom !== zoom) return Infinity;
        return vehicleOffsetFromMapCenter(marker);
      },
      { intervals: [16] },
    )
    .toBeLessThanOrEqual(1);
}

async function expectVehicleFocusedOnMap(
  page: Page,
  marker: Locator,
  naverMap: { snapshot: () => Promise<{ zoom: number }> },
  zoom = 16,
) {
  await expectVehicleCenteredOnMap(page, marker, naverMap, zoom);
  await expect(marker).toBeFocused();
}

async function expectMapToFillRegion(page: Page) {
  // SDK 크기 변경을 예약하는 ResizeObserver까지 전달된 뒤 canvas를 확인한다.
  await map(page).evaluate(
    (region) =>
      new Promise<void>((resolve) => {
        const observer = new ResizeObserver(() => {
          observer.disconnect();
          resolve();
        });
        observer.observe(region);
      }),
  );
  await expect
    .poll(() =>
      page.getByTestId("route-map").evaluate((element) => {
        const region = element.closest('[aria-label="노선 지도"]');
        if (region === null) throw new Error("지도 영역이 없습니다");
        const canvas = element.getBoundingClientRect();
        const visible = region.getBoundingClientRect();
        return Math.max(
          Math.abs(element.clientWidth - region.clientWidth),
          Math.abs(element.clientHeight - (window.visualViewport?.height ?? window.innerHeight)),
          Math.max(0, canvas.left - visible.left),
          Math.max(0, canvas.top - visible.top),
          Math.max(0, visible.right - canvas.right),
          Math.max(0, visible.bottom - canvas.bottom),
          Math.abs(canvas.left + canvas.width / 2 - visible.left - visible.width / 2),
          Math.abs(canvas.top + canvas.height / 2 - visible.top - visible.height / 2),
        );
      }),
    )
    .toBeLessThanOrEqual(1);
}

async function mapSetSizeCount(page: Page) {
  const count = await map(page).getByTestId("naver-map-test-double").getAttribute("data-set-size-count");
  if (count === null) throw new Error("지도 크기 변경 횟수가 없습니다");
  return Number(count);
}

async function dragHeader(page: Page, destinationY: number, { releaseDelayMs = 32 } = {}) {
  const bounds = await sheet(page).boundingBox();
  if (bounds === null) throw new Error("드래그할 시트가 없습니다");
  await page.mouse.move(bounds.x + 25, bounds.y + 12);
  await page.mouse.down();
  await page.mouse.move(bounds.x + 25, destinationY, { steps: 12 });
  await page.clock.runFor(releaseDelayMs);
  await page.mouse.up();
  await page.clock.runFor(32);
}

test.describe("지도 첫 화면 준비", () => {
  test.use({ holdMapInitialization: true, holdMapIdle: true });

  test("기본 지도를 먼저 표시하고 준비되면 노선·마커·위치 버튼을 차례로 표시한다", async ({ page, naverMap }) => {
    await page.goto(`/routes/${routeId}`);
    const region = map(page);
    const canvas = region.getByTestId("route-map");
    const basemap = region.getByTestId("naver-map-test-double");
    const paths = region.getByTestId("naver-polyline").locator("polyline");
    const buses = canvas.locator("[data-marker-kind='vehicle'] button");
    const loading = region.getByRole("status").filter({ hasText: "지도를 불러오는 중이에요" });
    await expect(basemap).toBeVisible();
    await expect(loading).toHaveCount(0);
    await expect(region.getByRole("status")).toHaveCount(0);
    await expect(region).toHaveAttribute("aria-busy", "true");
    await expect(canvas).toHaveAttribute("data-overlays-ready", "false");
    await expect(canvas).toHaveAttribute("aria-hidden", "true");
    await expect(canvas).toHaveAttribute("inert", "");
    await expect(basemap).toHaveCSS("visibility", "visible");
    await expect(paths).toHaveCount(2);
    await expect(paths.nth(0)).toHaveAttribute("stroke-opacity", "0");
    await expect(paths.nth(1)).toHaveAttribute("stroke-opacity", "0");
    await expect(buses).toHaveCount(2);
    await expect(buses.nth(0)).toHaveCSS("visibility", "hidden");
    await expect(buses.nth(1)).toHaveCSS("visibility", "hidden");
    await expect(canvas.locator("[data-map-arrow-direction]").first()).toHaveCSS("visibility", "hidden");
    await expect(region.getByRole("button", { name: /부근 버스/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toHaveCount(0);
    await expectMapToFillRegion(page);
    const originalCanvas = await canvas.elementHandle();

    await naverMap.initialize();
    await page.clock.runFor(32);
    await expect(basemap).toBeVisible();
    await expect(loading).toHaveCount(0);
    await expect(region).toHaveAttribute("aria-busy", "true");
    await expect(paths.nth(0)).toHaveAttribute("stroke-opacity", "0");
    await expect(paths.nth(1)).toHaveAttribute("stroke-opacity", "0");
    await naverMap.finishIdle();
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toHaveCount(0);

    await page.clock.runFor(16);
    await expect(paths.nth(0)).toHaveAttribute("stroke-opacity", "0.95");
    await expect(paths.nth(1)).toHaveAttribute("stroke-opacity", "0.95");
    await expect(canvas).toHaveAttribute("data-overlays-ready", "false");
    await expect(buses.nth(0)).toHaveCSS("visibility", "hidden");
    await expect(buses.nth(1)).toHaveCSS("visibility", "hidden");
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toHaveCount(0);

    await page.clock.runFor(16);
    await expect(region).toHaveAttribute("aria-busy", "false");
    await expect(canvas).toHaveAttribute("data-overlays-ready", "true");
    await expect(canvas).toHaveAttribute("aria-hidden", "false");
    await expect(canvas).not.toHaveAttribute("inert", "");
    await expect(buses.nth(0)).toHaveCSS("visibility", "visible");
    await expect(buses.nth(1)).toHaveCSS("visibility", "visible");
    await expect(region.getByRole("button", { name: /부근 버스/ })).toHaveCount(2);
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
    expect(await originalCanvas?.evaluate((element) => element.isConnected)).toBe(true);

    await naverMap.setZoom(14);
    await tab(page, "DOWN").click();
    await page.clock.runFor(32);
    await expect(loading).toHaveCount(0);
    await expect(region).toHaveAttribute("aria-busy", "false");
    await expect(canvas).toHaveAttribute("data-overlays-ready", "true");
    await expect(paths.nth(0)).toHaveAttribute("stroke-opacity", "0.95");
    await expect(paths.nth(1)).toHaveAttribute("stroke-opacity", "0.95");
    await expect(region.getByRole("button", { name: /부근 버스/ })).toHaveCount(1);
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
    await originalCanvas?.dispose();
  });

  test("native 초기화가 멈춰도 10초 뒤 오류를 안내하고 목록과 방향 선택을 유지한다", async ({ page }) => {
    await page.goto(`/routes/${routeId}`);
    await expect(map(page).getByTestId("naver-map-test-double")).toBeVisible();
    await expect(map(page)).toHaveAttribute("aria-busy", "true");
    await page.clock.runFor(9_999);
    await expect(map(page).getByText("지도를 불러오지 못했어요", { exact: true })).toHaveCount(0);
    await expect(map(page)).toHaveAttribute("aria-busy", "true");

    await page.clock.runFor(1);
    await expect(map(page).getByText("지도를 불러오지 못했어요", { exact: true })).toBeVisible();
    await expect(map(page).getByText("정류장 목록은 계속 확인할 수 있어요", { exact: true })).toBeVisible();
    await expect(map(page)).toHaveAttribute("aria-busy", "false");
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toHaveCount(0);
    await expect(map(page).getByTestId("naver-map-test-double")).toHaveCount(0);
    await tab(page, "DOWN").click();
    await page.clock.runFor(32);
    await expect(tab(page, "DOWN")).toHaveAttribute("aria-pressed", "true");
    const stop = list(page).locator("li[data-stop-sequence='23'] button[aria-expanded]");
    await stop.scrollIntoViewIfNeeded();
    await stop.click();
    await page.clock.runFor(32);
    await expect(stop).toHaveAttribute("aria-expanded", "true");
  });

  test("처음 표시하기 전 SDK 오류가 나도 목록과 방향 선택은 계속 사용할 수 있다", async ({ page, naverMap }) => {
    await page.goto(`/routes/${routeId}`);
    await expect(map(page).getByTestId("naver-map-test-double")).toHaveCount(1);
    await expect(map(page)).toHaveAttribute("aria-busy", "true");
    await naverMap.failNative("all");
    await page.evaluate(() => window.navermap_authFailure?.());
    await expect(map(page).getByText("지도를 불러오지 못했어요", { exact: true })).toBeVisible();
    await expect(map(page).getByText("정류장 목록은 계속 확인할 수 있어요", { exact: true })).toBeVisible();
    await expect(map(page)).toHaveAttribute("aria-busy", "false");
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toHaveCount(0);
    await tab(page, "DOWN").click();
    await page.clock.runFor(32);
    await expect(tab(page, "DOWN")).toHaveAttribute("aria-pressed", "true");
    const stop = list(page).locator("li[data-stop-sequence='23'] button[aria-expanded]");
    await stop.scrollIntoViewIfNeeded();
    await stop.click();
    await page.clock.runFor(32);
    await expect(stop).toHaveAttribute("aria-expanded", "true");
  });
});

test("첫 화면은 절반 높이에 전체 노선을 맞추고 줌에 따라 정류장만 숨기며 차량·방향 화살표는 유지한다", async ({
  page,
  naverMap,
}, testInfo) => {
  await openMap(page);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  expect(await height(sheet(page))).toBeCloseTo(422, 0);
  expect(await height(map(page))).toBeCloseTo(422, 0);
  await expect(sheet(page).locator("[data-selected='true']")).toHaveCount(0);
  await expect(map(page).locator("button[data-map-stop-sequence]:visible")).toHaveCount(0);
  await expect(map(page).getByRole("button", { name: /부근 버스/ })).toHaveCount(2);
  await expect(map(page).getByTestId("naver-polyline")).toHaveCount(2);
  const routeCoverage = await map(page)
    .getByTestId("naver-map-test-double")
    .evaluate((layer) => {
      const region = layer.closest('[aria-label="노선 지도"]');
      if (region === null) throw new Error("지도 영역이 없습니다");
      const bounds = region.getBoundingClientRect();
      const canvas = layer.getBoundingClientRect();
      return [...layer.querySelectorAll("polyline")].map((line) => {
        const points = [...line.points];
        return {
          pointCount: points.length,
          inside: points.every((point) => {
            const x = canvas.left + point.x - bounds.left;
            const y = canvas.top + point.y - bounds.top;
            return x >= 11 && x <= bounds.width - 11 && y >= 27 && y <= bounds.height - 27;
          }),
        };
      });
    });
  expect(routeCoverage).toHaveLength(2);
  routeCoverage.forEach((path) => {
    expect(path.pointCount).toBeGreaterThan(1);
    expect(path.inside).toBe(true);
  });
  expect(await map(page).locator("[data-marker-kind='arrow']").count()).toBeGreaterThan(0);
  await expect(sheet(page).getByText("15–25분", { exact: true })).toBeVisible();
  await expect(sheet(page).getByText("3대 운행 중", { exact: true })).toBeVisible();
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-half.png" });

  await naverMap.setZoom(14);
  await expect(map(page).locator("button[data-map-stop-sequence]:visible")).toHaveCount(24);
  await expect(
    map(page).getByRole("button", {
      name: `테스트 종점 ${directionLabels.UP} 정류장 지도에서 선택`,
      exact: true,
    }),
  ).toHaveAttribute("data-map-stop-sequence", "12");
  await expect(
    map(page).getByRole("button", {
      name: `테스트 종점 ${directionLabels.DOWN} 정류장 지도에서 선택`,
      exact: true,
    }),
  ).toHaveAttribute("data-map-stop-sequence", "13");
  expect(await map(page).locator("[data-marker-kind='arrow']").count()).toBeGreaterThan(0);
  await naverMap.setZoom(13);
  await expect(map(page).locator("button[data-map-stop-sequence]:visible")).toHaveCount(0);
  await expect(map(page).getByRole("button", { name: /부근 버스/ })).toHaveCount(2);
});

test("축소에서는 선택한 방향의 화살표만 표시하고 확대하면 두 방향을 표시한다", async ({ page, naverMap }) => {
  await openMap(page);
  const visibleDirections = () =>
    map(page)
      .locator("[data-map-arrow-direction]:visible")
      .evaluateAll((elements) =>
        [...new Set(elements.map((element) => element.getAttribute("data-map-arrow-direction")))].sort(),
      );
  expect(await visibleDirections()).toEqual(["UP"]);
  const initialView = await naverMap.snapshot();
  const original = await map(page).evaluateHandle((region) => ({
    layer: region.querySelector('[data-testid="naver-map-test-double"]'),
    lines: [...region.querySelectorAll('[data-testid="naver-polyline"]')],
    arrows: [...region.querySelectorAll("[data-map-arrow-direction]")],
  }));

  await tab(page, "DOWN").click();
  await page.clock.runFor(32);
  expect(await visibleDirections()).toEqual(["DOWN"]);
  expect(await naverMap.snapshot()).toEqual(initialView);

  await naverMap.setZoom(14);
  expect(await visibleDirections()).toEqual(["DOWN", "UP"]);
  expect(await naverMap.snapshot()).toEqual({ ...initialView, zoom: 14 });

  await naverMap.setZoom(13.9);
  expect(await visibleDirections()).toEqual(["DOWN"]);
  const contractedView = await naverMap.snapshot();
  expect(contractedView).toEqual({ ...initialView, zoom: 13.9 });
  await tab(page, "UP").click();
  await page.clock.runFor(32);
  expect(await visibleDirections()).toEqual(["UP"]);
  expect(await naverMap.snapshot()).toEqual(contractedView);
  expect(
    await original.evaluate(
      ({ layer, lines, arrows }) =>
        layer?.isConnected && lines.every((line) => line.isConnected) && arrows.every((arrow) => arrow.isConnected),
    ),
  ).toBe(true);
  await expect(map(page).getByTestId("naver-map-test-double")).toHaveCount(1);
  await original.dispose();
});

test("GL 지도의 휠과 더블클릭은 부드럽게 확대하며 노선·마커 DOM을 유지한다", async ({ page, naverMap }) => {
  await openMap(page);
  await naverMap.setZoom(14);
  await naverMap.panToStop(11);
  const before = await naverMap.snapshot();
  const bounds = await map(page).boundingBox();
  if (bounds === null) throw new Error("지도 영역이 없습니다");
  const nativeLayer = map(page).getByTestId("naver-map-test-double");
  const pin = map(page).locator("button[data-map-stop-sequence='11']");
  const pinWidth = await pin.evaluate((element) => element.getBoundingClientRect().width);
  const persistent = await map(page).evaluateHandle((region) => [
    ...region.querySelectorAll(
      '[data-testid="naver-map-test-double"], [data-testid="naver-polyline"], [data-marker-kind], [data-marker-kind="vehicle"] img',
    ),
  ]);

  await page.mouse.move(bounds.x + bounds.width / 2 + 60, bounds.y + bounds.height / 2);
  await page.mouse.wheel(0, -120);
  await expect(nativeLayer).toHaveAttribute("data-zoom-animating", "true");
  await page.clock.runFor(80);
  const duringWheel = await naverMap.snapshot();
  expect(duringWheel.zoom).toBeGreaterThan(14);
  expect(duringWheel.zoom).toBeLessThan(15);
  expect(await pin.evaluate((element) => element.getBoundingClientRect().width)).toBe(pinWidth);
  expect(await persistent.evaluate((nodes) => nodes.every((node) => node.isConnected))).toBe(true);
  await page.clock.runFor(300);
  expect((await naverMap.snapshot()).zoom).toBe(15);
  expect((await naverMap.snapshot()).longitude).not.toBe(before.longitude);

  await page.mouse.wheel(0, 120);
  await expect(nativeLayer).toHaveAttribute("data-zoom-animating", "true");
  await page.clock.runFor(300);
  const restored = await naverMap.snapshot();
  expect(restored.zoom).toBe(14);
  expect(restored.latitude).toBeCloseTo(before.latitude, 8);
  expect(restored.longitude).toBeCloseTo(before.longitude, 8);

  await page.mouse.dblclick(bounds.x + bounds.width - 40, bounds.y + 30);
  await expect(nativeLayer).toHaveAttribute("data-zoom-animating", "true");
  await page.clock.runFor(80);
  const duringDoubleClick = await naverMap.snapshot();
  expect(duringDoubleClick.zoom).toBeGreaterThan(14);
  expect(duringDoubleClick.zoom).toBeLessThan(15);
  expect(await persistent.evaluate((nodes) => nodes.every((node) => node.isConnected))).toBe(true);
  await page.clock.runFor(300);
  expect((await naverMap.snapshot()).zoom).toBe(15);
  await expect(map(page).getByTestId("naver-polyline")).toHaveCount(2);
  await persistent.dispose();
});

test("반복 확대와 이동에도 지도와 마커 DOM을 유지하고 정류장 포커스가 남는다", async ({ page, naverMap }) => {
  await openMap(page);
  await naverMap.setZoom(14);
  await naverMap.panToStop(11);
  const pin = map(page).locator("button[data-map-stop-sequence='11']");
  await pin.focus();
  const observation = await map(page).evaluateHandle((region) => {
    const layer = region.querySelector('[data-testid="naver-map-test-double"]');
    const removed: string[] = [];
    const observer = new MutationObserver((records) => {
      records.forEach((record) => {
        record.removedNodes.forEach((node) => {
          if (node instanceof HTMLElement && node.matches("[data-marker-kind], button[data-map-stop-sequence]"))
            removed.push(node.outerHTML);
        });
      });
    });
    observer.observe(region, { childList: true, subtree: true });
    return { layer, removed, observer };
  });

  for (const zoom of [15, 16, 14]) await naverMap.setZoom(zoom);
  await naverMap.panToStop(23);
  await page.clock.runFor(32);

  await expect(pin).toBeFocused();
  const changes = await observation.evaluate(({ layer, removed, observer }) => {
    observer.disconnect();
    return { mapConnected: layer?.isConnected, removed };
  });
  expect(changes).toEqual({ mapConnected: true, removed: [] });
  await observation.dispose();
  expect(await map(page).locator("[data-marker-kind='arrow']:visible").count()).toBeGreaterThan(0);
});

test("지도 버스를 누르면 정류장을 펼치지 않고 현재 위치로 이동하며 재클릭도 다시 스크롤한다", async ({
  page,
  naverMap,
}) => {
  await openMap(page);
  const bus = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  const tracker = list(page).getByRole("button", {
    name: "상행 정류장 8 정류장, 도착 중, 현재 잔여 좌석 5석",
    exact: true,
  });
  await bus.click();
  await expectStopFocusedInList(page, 8);
  await expect(list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  await expect(list(page).locator("button[aria-expanded='true']")).toHaveCount(0);
  await expect(list(page).locator("[data-selected='true']")).toHaveCount(0);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await expectVehicleCenteredOnMap(page, bus, naverMap);
  await expect(bus).toHaveAttribute("aria-pressed", "true");
  await expect(tracker).toHaveAttribute("aria-pressed", "true");
  await expect(list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]")).toBeFocused();

  await list(page).evaluate((element) => {
    element.scrollTop = 0;
  });
  await page.clock.runFor(32);
  await bus.click();
  await expectStopFocusedInList(page, 8);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await expect(list(page).locator("button[aria-expanded='true']")).toHaveCount(0);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expectVehicleCenteredOnMap(page, bus, naverMap);
  await expect(bus).toHaveAttribute("aria-pressed", "true");
  await expect(tracker).toHaveAttribute("aria-pressed", "true");

  const otherTracker = list(page).getByRole("button", {
    name: "상행 정류장 4 정류장, 도착 중, 현재 잔여 좌석 2석",
    exact: true,
  });
  const otherMarker = map(page).getByRole("button", {
    name: "상행 정류장 4 부근 버스, 현재 잔여 좌석 2석",
    exact: true,
  });
  await otherTracker.scrollIntoViewIfNeeded();
  await naverMap.setZoom(17);
  await otherTracker.click();
  await expectVehicleFocusedOnMap(page, otherMarker, naverMap, 17);
  await expect(bus).toHaveAttribute("aria-pressed", "false");
  await expect(tracker).toHaveAttribute("aria-pressed", "false");
  await expect(otherTracker).toHaveAttribute("aria-pressed", "true");
  await expect(otherMarker).toHaveAttribute("aria-pressed", "true");
  await tab(page, "DOWN").click();
  await expect(map(page).locator("button[data-map-vehicle-key][aria-pressed='true']")).toHaveCount(0);
  await tab(page, "UP").click();
  await expect(map(page).locator("button[data-map-vehicle-key][aria-pressed='true']")).toHaveCount(0);
  await expect(list(page).locator("button[aria-pressed='true']")).toHaveCount(0);
});

test("지도 버스의 목록 이동은 중간 위치를 거쳐 완료되고 수동 휠 입력은 자동 이동과 포커스를 취소한다", async ({
  page,
  naverMap,
}) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await openMap(page);
  const bus = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  const stop = list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]");
  const originalView = await naverMap.snapshot();
  expect(await list(page).evaluate((element) => element.scrollTop)).toBe(0);

  await bus.click();
  await page.clock.runFor(16 * 5);
  const intermediateScroll = await list(page).evaluate((element) => element.scrollTop);
  expect(intermediateScroll).toBeGreaterThan(0);
  const intermediateView = await naverMap.snapshot();
  expect(intermediateView.zoom).toBeGreaterThan(originalView.zoom);
  expect(intermediateView.zoom).toBeLessThan(16);
  await expect(bus).toBeInViewport();
  await expect(bus).toHaveAttribute("aria-pressed", "true");
  await expect(stop).not.toBeFocused();
  await expectStopFocusedInList(page, 8);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeGreaterThan(intermediateScroll);
  await expect(stop).toHaveAttribute("aria-expanded", "false");
  await expectVehicleCenteredOnMap(page, bus, naverMap);
  await expect(stop).toBeFocused();
  await expect(bus).toHaveAttribute("aria-pressed", "true");

  await list(page).evaluate((element) => {
    element.scrollTop = 0;
  });
  await bus.click();
  await page.clock.runFor(16 * 5);
  const interruptedScroll = await list(page).evaluate((element) => element.scrollTop);
  expect(interruptedScroll).toBeGreaterThan(0);
  const bounds = await list(page).boundingBox();
  if (bounds === null) throw new Error("스크롤할 목록이 없습니다");
  await page.mouse.move(bounds.x + bounds.width - 12, bounds.y + bounds.height / 2);
  await page.mouse.wheel(0, -120);
  await expect
    .poll(async () => {
      await page.clock.runFor(16);
      return list(page).evaluate((element) => element.scrollTop);
    })
    .toBeLessThan(interruptedScroll);
  const manualScroll = await list(page).evaluate((element) => element.scrollTop);
  await tab(page, "UP").focus();
  await page.clock.runFor(16 * 24);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeLessThanOrEqual(manualScroll + 1);
  await expect(tab(page, "UP")).toBeFocused();
  await expect(stop).not.toBeFocused();
  await expect(stop).toHaveAttribute("aria-expanded", "false");
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expectVehicleCenteredOnMap(page, bus, naverMap);
  await expect(tab(page, "UP")).toBeFocused();
  await expect(bus).toHaveAttribute("aria-pressed", "true");
});

test("70% 시트에서 버스 선택은 펼친 정류장을 유지하고 갱신된 현재 위치로 확대한다", async ({ page, naverMap, api }) => {
  await openMap(page);
  const expandedStop = list(page).locator("li[data-stop-sequence='6'] button[aria-expanded]");
  await expandedStop.scrollIntoViewIfNeeded();
  await expandedStop.click();
  await page.clock.runFor(32);
  await expect(expandedStop).toHaveAttribute("aria-expanded", "true");
  await handle(page).press("ArrowUp");
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expectSheetAndMapHeights(page, 844 * 0.7, 844 * 0.3);
  await naverMap.setZoom(13);
  await naverMap.panToStop(8);
  const bus = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  await bus.click();
  await expectStopFocusedInList(page, 8);
  await expect(expandedStop).toHaveAttribute("aria-expanded", "true");
  await expect(list(page).locator("li[data-stop-sequence='6']")).toHaveAttribute("data-selected", "true");
  await expect(list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expectVehicleCenteredOnMap(page, bus, naverMap);
  await expect(bus).toHaveAttribute("aria-pressed", "true");

  const vehicle = api.data.vehicles.vehicles.find((item) => item.vehicleId === "bus-1");
  const nextStop = api.data.board.stops.find((item) => item.direction === "UP" && item.sequence === 10);
  if (vehicle === undefined || nextStop === undefined) throw new Error("갱신할 버스와 정류장이 없습니다");
  vehicle.currentStopSequence = nextStop.sequence;
  vehicle.stopId = nextStop.stopId;
  vehicle.stopName = nextStop.name;
  api.data.vehicles.observation.observedAt = "2026-09-16T08:01:00+09:00";
  api.data.vehicles.observation.staleAt = "2026-09-16T08:06:00+09:00";
  const refreshedVehicles = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.vehicles && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await (await refreshedVehicles).finished();
  await page.clock.runFor(32);
  const latestBus = map(page).getByRole("button", {
    name: `${nextStop.name} 부근 버스, 현재 잔여 좌석 5석`,
    exact: true,
  });
  const latestTracker = list(page).getByRole("button", {
    name: `${nextStop.name} 정류장, 도착 중, 현재 잔여 좌석 5석`,
    exact: true,
  });
  await expect(latestBus).toHaveAttribute("aria-pressed", "true");
  await expect(latestTracker).toHaveAttribute("aria-pressed", "true");
  await naverMap.panToStop(nextStop.sequence);
  await latestBus.click();
  await expectStopFocusedInList(page, nextStop.sequence);
  await expect(expandedStop).toHaveAttribute("aria-expanded", "true");
  await expect(list(page).locator("li[data-stop-sequence='10'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expectVehicleCenteredOnMap(page, latestBus, naverMap);
  await expect(latestBus).toHaveAttribute("aria-pressed", "true");
  await expect(latestTracker).toHaveAttribute("aria-pressed", "true");
});

test("최소 시트에서 지도 버스를 누르면 절반 높이로 열고 현재 정류장을 펼치지 않은 채 보여 준다", async ({
  page,
  naverMap,
}) => {
  await openMap(page);
  await handle(page).press("Home");
  await expect(sheet(page)).toHaveAttribute("data-snap", "minimum");
  await expect(list(page)).toHaveCount(0);
  const bus = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  await bus.click();
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expectSheetAndMapHeights(page, 422, 422);
  await expectStopFocusedInList(page, 8);
  await expect(list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  await expect(list(page).locator("button[aria-expanded='true']")).toHaveCount(0);
  await expectVehicleCenteredOnMap(page, bus, naverMap);
  await expect(bus).toHaveAttribute("aria-pressed", "true");
  await expect(list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]")).toBeFocused();
});

test("목록 버스는 70% 시트와 펼침·스크롤을 유지하며 지도에서 같은 버스의 최신 표시 위치를 찾는다", async ({
  page,
  naverMap,
  api,
}) => {
  await openMap(page);
  const expandedStop = list(page).locator("li[data-stop-sequence='6'] button[aria-expanded]");
  await expandedStop.scrollIntoViewIfNeeded();
  await expandedStop.click();
  await page.clock.runFor(32);
  await handle(page).press("ArrowUp");
  await expectSheetAndMapHeights(page, 844 * 0.7, 844 * 0.3);
  const tracker = list(page).getByRole("button", {
    name: "상행 정류장 8 정류장, 도착 중, 현재 잔여 좌석 5석",
    exact: true,
  });
  await tracker.scrollIntoViewIfNeeded();
  const key = await tracker.evaluate(
    (element) => element.closest<HTMLElement>("[data-live-vehicle-key]")?.dataset.liveVehicleKey,
  );
  expect(key).toBeDefined();
  const marker = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  await expect(marker).toHaveAttribute("data-map-vehicle-key", key!);
  await naverMap.panToStop(1);
  const before = await naverMap.snapshot();
  const scroll = await list(page).evaluate((element) => element.scrollTop);

  await tracker.click();
  await expectVehicleFocusedOnMap(page, marker, naverMap);
  expect((await naverMap.snapshot()).zoom).toBe(16);
  expect(await naverMap.snapshot()).not.toEqual(before);
  await expect(marker).toHaveAttribute("aria-pressed", "true");
  await expect(tracker).toHaveAttribute("aria-pressed", "true");
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(scroll, 0);
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expect(expandedStop).toHaveAttribute("aria-expanded", "true");
  await expect(list(page).locator("li[data-stop-sequence='6']")).toHaveAttribute("data-selected", "true");
  await expect(list(page).locator("li[data-stop-sequence='8'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "false",
  );

  await naverMap.panToStop(1);
  await tracker.click();
  await expectVehicleFocusedOnMap(page, marker, naverMap);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(scroll, 0);

  const vehicle = api.data.vehicles.vehicles.find((item) => item.vehicleId === "bus-1");
  const nextStop = api.data.board.stops.find((item) => item.direction === "UP" && item.sequence === 10);
  if (vehicle === undefined || nextStop === undefined) throw new Error("갱신할 버스와 정류장이 없습니다");
  vehicle.currentStopSequence = nextStop.sequence;
  vehicle.stopId = nextStop.stopId;
  vehicle.stopName = nextStop.name;
  api.data.vehicles.observation.observedAt = "2026-09-16T08:01:00+09:00";
  api.data.vehicles.observation.staleAt = "2026-09-16T08:06:00+09:00";
  const refreshedVehicles = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.vehicles && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await (await refreshedVehicles).finished();
  await page.clock.runFor(32);
  const latestTracker = list(page).getByRole("button", {
    name: `${nextStop.name} 정류장, 도착 중, 현재 잔여 좌석 5석`,
    exact: true,
  });
  const latestMarker = map(page).getByRole("button", {
    name: `${nextStop.name} 부근 버스, 현재 잔여 좌석 5석`,
    exact: true,
  });
  await latestTracker.scrollIntoViewIfNeeded();
  const latestScroll = await list(page).evaluate((element) => element.scrollTop);
  await naverMap.panToStop(1);
  await latestTracker.click();
  await expectVehicleFocusedOnMap(page, latestMarker, naverMap);
  await expect(latestMarker).toHaveAttribute("data-map-vehicle-key", key!);
  await expect(latestMarker).toHaveAttribute("aria-pressed", "true");
  await expect(latestTracker).toHaveAttribute("aria-pressed", "true");
  expect((await naverMap.snapshot()).zoom).toBe(16);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(latestScroll, 0);
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expect(expandedStop).toHaveAttribute("aria-expanded", "true");
});

test("목록 버스를 누르면 이동 중인 지도 마커를 선택한 채 중간 시점을 거쳐 부드럽게 확대한다", async ({
  page,
  naverMap,
  api,
}) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  api.cacheMaxAgeSeconds = 5;
  const vehicle = api.data.vehicles.vehicles.find((item) => item.vehicleId === "bus-1");
  if (vehicle === undefined) throw new Error("이동할 버스가 없습니다");
  vehicle.phase = "IN_TRANSIT";
  await openMap(page);
  const tracker = list(page).getByRole("button", {
    name: "상행 정류장 8 정류장, 다음 정류장으로 이동 중, 현재 잔여 좌석 5석",
    exact: true,
  });
  const marker = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  await tracker.scrollIntoViewIfNeeded();
  await naverMap.setZoom(13);
  await naverMap.panToStop(1);
  const before = await naverMap.snapshot();
  const initialDistance = await vehicleOffsetFromMapCenter(marker);
  const initialTrackerTop = await tracker.evaluate(
    (element) => element.closest<HTMLElement>("[data-live-vehicle-key]")!.getBoundingClientRect().top,
  );
  const scroll = await list(page).evaluate((element) => element.scrollTop);

  await tracker.click();
  await page.clock.runFor(16 * 5);
  const intermediate = await naverMap.snapshot();
  const intermediateDistance = await vehicleOffsetFromMapCenter(marker);
  expect(intermediate).not.toEqual(before);
  expect(intermediate.zoom).toBeGreaterThan(before.zoom);
  expect(intermediate.zoom).toBeLessThan(16);
  await expect(marker).toHaveAttribute("aria-pressed", "true");
  await expect(tracker).toHaveAttribute("aria-pressed", "true");
  expect(intermediateDistance).toBeGreaterThan(1);
  expect(intermediateDistance).toBeLessThan(initialDistance);
  await expectVehicleFocusedOnMap(page, marker, naverMap);
  expect(
    await tracker.evaluate(
      (element) => element.closest<HTMLElement>("[data-live-vehicle-key]")!.getBoundingClientRect().top,
    ),
  ).toBeGreaterThan(initialTrackerTop);
  expect((await naverMap.snapshot()).zoom).toBe(16);
  await expect(marker).toHaveAttribute("aria-pressed", "true");
  await expect(tracker).toHaveAttribute("aria-pressed", "true");
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(scroll, 0);
  await expect(list(page).locator("button[aria-expanded='true']")).toHaveCount(0);
});

test("전체 시트의 목록 버스를 누르면 절반 높이로 열고 지도 마커에 포커스하며 목록으로 되돌아가지 않는다", async ({
  page,
  naverMap,
}) => {
  await openMap(page);
  await handle(page).press("End");
  await expect(sheet(page)).toHaveAttribute("data-snap", "full");
  await expect(map(page)).toHaveCount(0);
  const tracker = list(page).getByRole("button", {
    name: "상행 정류장 8 정류장, 도착 중, 현재 잔여 좌석 5석",
    exact: true,
  });
  await tracker.scrollIntoViewIfNeeded();
  const scroll = await list(page).evaluate((element) => element.scrollTop);
  const zoom = (await naverMap.snapshot()).zoom;
  await tracker.click();
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expectSheetAndMapHeights(page, 422, 422);
  const marker = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 5석", exact: true });
  await expectVehicleFocusedOnMap(page, marker, naverMap);
  expect((await naverMap.snapshot()).zoom).toBe(Math.max(16, zoom));
  await expect(marker).toHaveAttribute("aria-pressed", "true");
  await expect(tracker).toHaveAttribute("aria-pressed", "true");
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(scroll, 0);
  await expect(list(page).locator("button[aria-expanded='true']")).toHaveCount(0);
  await page.clock.runFor(16 * 24);
  await expect(marker).toBeFocused();
  expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(scroll, 0);

  await naverMap.panToStop(11);
  const pannedView = await naverMap.snapshot();
  await handle(page).press("End");
  await expect(map(page)).toHaveCount(0);
  await handle(page).press("Home");
  await expect(sheet(page)).toHaveAttribute("data-snap", "minimum");
  await expect
    .poll(async () => {
      await page.clock.runFor(32);
      return map(page).count();
    })
    .toBe(1);
  await expectMapToFillRegion(page);
  expect(await naverMap.snapshot()).toEqual(pannedView);
});

test("정류장명과 현재 좌석을 함께 표시하고 줌과 잔여석 응답이 바뀌어도 차량 핀과 이미지 DOM을 유지한다", async ({
  page,
  api,
  naverMap,
}) => {
  const vehicle = api.data.vehicles.vehicles.find((item) => item.vehicleId === "bus-1");
  if (vehicle === undefined) throw new Error("갱신할 버스가 없습니다");
  vehicle.seat = { kind: "EXACT", remaining: 39 };
  await openMap(page);
  const bus = map(page).getByRole("button", { name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 39석", exact: true });
  const count = bus.getByText("현재 39석", { exact: true });
  const stopName = map(page).getByText("상행 정류장 8", { exact: true });
  await expect(bus).toBeVisible();
  await expect(count).toBeHidden();
  await expect(stopName).toBeHidden();
  const observation = await bus.evaluateHandle((element) => {
    const image = element.querySelector("img");
    const pin = image?.parentElement;
    if (image === null || pin === null || pin === undefined) throw new Error("차량 핀 이미지가 없습니다");
    const removed: string[] = [];
    const observer = new MutationObserver((records) => {
      records.forEach((record) => {
        record.removedNodes.forEach((node) => {
          if (node instanceof Element) removed.push(node.outerHTML);
        });
      });
    });
    observer.observe(element, { childList: true, subtree: true });
    return { element, pin, image, removed, observer };
  });
  await naverMap.setZoom(13.9);
  await expect(count).toBeHidden();
  await expect(stopName).toBeHidden();
  await naverMap.setZoom(14);
  await expect(count).toBeVisible();
  await expect(stopName).toBeVisible();
  await naverMap.setZoom(13.9);
  await expect(count).toBeHidden();
  await expect(stopName).toBeHidden();
  await expect(bus).toBeVisible();
  vehicle.seat = { kind: "EXACT", remaining: 0 };
  api.data.vehicles.observation.observedAt = "2026-09-16T08:01:00+09:00";
  api.data.vehicles.observation.staleAt = "2026-09-16T08:06:00+09:00";
  const refreshedVehicles = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.vehicles && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await (await refreshedVehicles).finished();
  await page.clock.runFor(32);
  const updatedBus = map(page).getByRole("button", {
    name: "상행 정류장 8 부근 버스, 현재 잔여 좌석 0석",
    exact: true,
  });
  const updatedCount = updatedBus.getByText("현재 0석", { exact: true });
  await expect(updatedCount).toBeHidden();
  await naverMap.setZoom(14);
  await expect(updatedCount).toBeVisible();
  await expect(stopName).toBeVisible();
  expect(
    await observation.evaluate(({ element, pin, image, removed, observer }) => {
      observer.disconnect();
      return {
        connected: element.isConnected && pin.isConnected && image.isConnected,
        sameImage: element.querySelector("img") === image,
        samePin: image.parentElement === pin,
        removed,
      };
    }),
  ).toEqual({ connected: true, sameImage: true, samePin: true, removed: [] });
  await observation.dispose();
});

test("키보드로 최소·절반·70%·전체를 선택하고 목록 스크롤은 시트 높이를 바꾸지 않는다", async ({ page }, testInfo) => {
  await openMap(page);
  await expect(handle(page)).toHaveAttribute("aria-valuemax", "3");
  await expect(handle(page)).toHaveAttribute("aria-valuenow", "1");
  await handle(page).focus();
  await handle(page).press("Home");
  await expect(sheet(page)).toHaveAttribute("data-snap", "minimum");
  await expect(handle(page)).toHaveAttribute("aria-valuenow", "0");
  await expect(list(page)).toHaveCount(0);
  await expect(tab(page, "UP")).toBeVisible();
  await expect(sheet(page).getByText("05:00–23:00", { exact: true })).toBeVisible();
  expect(await height(sheet(page))).toBeLessThan(422);
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-minimum.png" });

  await handle(page).press("ArrowUp");
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  const originalHeight = await height(sheet(page));
  const bounds = await list(page).boundingBox();
  if (bounds === null) throw new Error("스크롤할 목록이 없습니다");
  await page.mouse.move(bounds.x + bounds.width - 12, bounds.y + 30);
  await page.mouse.wheel(0, 450);
  await expect.poll(() => list(page).evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  expect(await height(sheet(page))).toBe(originalHeight);

  await handle(page).press("ArrowUp");
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expect(handle(page)).toHaveAttribute("aria-valuenow", "2");
  await expect(handle(page)).toHaveAttribute("aria-valuetext", "화면 70% 표시");
  await expectSheetAndMapHeights(page, 844 * 0.7, 844 * 0.3);
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-expanded.png" });

  await handle(page).press("End");
  await expect(sheet(page)).toHaveAttribute("data-snap", "full");
  await expect(handle(page)).toHaveAttribute("aria-valuenow", "3");
  expect(await height(sheet(page))).toBeCloseTo(844, 0);
  await expect(map(page)).toHaveCount(0);
  await expect(sheet(page).getByRole("link", { name: "노선 선택으로 돌아가기" })).toBeVisible();
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-full.png" });
  await handle(page).press("ArrowDown");
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await handle(page).press("ArrowDown");
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
});

test("핸들 클릭은 네 단계를 순환하고 느린 드래그는 70% 높이에 멈춘다", async ({ page }) => {
  await openMap(page);
  for (const snap of ["expanded", "full", "minimum", "half"]) {
    await handle(page).click();
    await page.clock.runFor(32);
    await expect(sheet(page)).toHaveAttribute("data-snap", snap);
  }

  const bounds = await sheet(page).boundingBox();
  if (bounds === null) throw new Error("드래그할 시트가 없습니다");
  const targetHeight = 844 * 0.7;
  await dragHeader(page, bounds.y + 12 - (targetHeight - bounds.height), { releaseDelayMs: 100 });
  await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
  await expect(sheet(page)).toHaveAttribute("data-dragging", "false");
  await expectSheetAndMapHeights(page, targetHeight, 844 - targetHeight);
});

test("헤더 드래그는 양 끝에 붙고 방향 버튼에서 시작한 드래그는 높이를 바꾸지 않는다", async ({ page }) => {
  await openMap(page);
  await dragHeader(page, 1);
  await expect(sheet(page)).toHaveAttribute("data-snap", "full");
  await dragHeader(page, 840);
  await expect(sheet(page)).toHaveAttribute("data-snap", "minimum");
  await handle(page).press("ArrowUp");
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");

  const bounds = await tab(page, "DOWN").boundingBox();
  if (bounds === null) throw new Error("방향 버튼이 없습니다");
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await page.mouse.down();
  await page.mouse.move(bounds.x + bounds.width / 2, 5, { steps: 8 });
  await page.mouse.up();
  await page.clock.runFor(32);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  expect(await height(sheet(page))).toBeCloseTo(422, 0);
});

for (const reducedMotion of ["reduce", "no-preference"] as const) {
  test(`뒤쪽 차량 응답에는 지도와 목록이 멈추고 앞선 응답이 오면 함께 이동한다 (${reducedMotion})`, async ({
    page,
    api,
  }) => {
    const durationMs = 20_000;
    api.cacheMaxAgeSeconds = durationMs / 1_000;
    const vehicle = api.data.vehicles.vehicles.find((item) => item.vehicleId === "bus-1");
    const nextStop = api.data.board.stops.find((item) => item.direction === "UP" && item.sequence === 9);
    if (vehicle === undefined || nextStop === undefined) throw new Error("이동할 버스와 정류장이 없습니다");
    vehicle.phase = "IN_TRANSIT";
    await page.emulateMedia({ reducedMotion });
    await openMap(page);
    const pin = map(page).getByRole("button", { name: /부근 버스, 현재 잔여 좌석 5석/ });
    const tracker = list(page)
      .getByRole("button", { name: /현재 잔여 좌석 5석/ })
      .locator("..");

    async function refreshVehicles() {
      const response = page.waitForResponse(
        (response) => new URL(response.url()).pathname === apiPaths.vehicles && response.status() === 200,
      );
      await page.clock.fastForward(durationMs);
      await (await response).finished();
      await page.clock.runFor(32);
    }

    async function positions() {
      return {
        map: await pin.evaluate((element) => {
          const bounds = element.getBoundingClientRect();
          return { x: bounds.x, y: bounds.y };
        }),
        list: await tracker.evaluate((element) => element.getBoundingClientRect().y),
      };
    }

    await refreshVehicles();
    const stopped = await positions();
    for (const phase of ["DEPARTED", "DEPARTED", "ARRIVING"] as const) {
      vehicle.phase = phase;
      await refreshVehicles();
      await page.clock.runFor(1_000);
      expect(await positions()).toEqual(stopped);
    }

    vehicle.phase = "DEPARTED";
    vehicle.currentStopSequence = nextStop.sequence;
    vehicle.stopId = nextStop.stopId;
    vehicle.stopName = nextStop.name;
    await refreshVehicles();
    await page.clock.runFor(durationMs / 2);
    const moved = await positions();
    expect(Math.hypot(moved.map.x - stopped.map.x, moved.map.y - stopped.map.y)).toBeGreaterThan(1);
    expect(moved.list).toBeGreaterThan(stopped.list + 1);
  });

  test(`드래그 중 지도와 채팅 위치가 시트 경계를 따라가고 지도 시점은 유지된다 (${reducedMotion})`, async ({
    page,
    naverMap,
  }) => {
    await page.emulateMedia({ reducedMotion });
    await openMap(page);
    await naverMap.setZoom(14);
    await naverMap.panToStop(11);
    const originalView = await naverMap.snapshot();
    const originalSetSizeCount = await mapSetSizeCount(page);
    const bounds = await sheet(page).boundingBox();
    if (bounds === null) throw new Error("드래그할 시트가 없습니다");
    const originY = bounds.y + 12;
    await page.mouse.move(bounds.x + 25, originY);
    await page.mouse.down();

    let previousOffset = 0;
    for (const offset of [-80, -150, -90, 80]) {
      for (let step = 1; step <= 4; step += 1) {
        const currentOffset = previousOffset + ((offset - previousOffset) * step) / 4;
        await page.mouse.move(bounds.x + 25, originY + currentOffset);
        await page.clock.runFor(32);
        const actualTop = await sheet(page).evaluate((element) => element.getBoundingClientRect().top);
        expect(Math.abs(actualTop - bounds.y - currentOffset)).toBeLessThanOrEqual(1);
        await expectMapToFillRegion(page);
        expect(await mapSetSizeCount(page)).toBe(originalSetSizeCount);
      }
      previousOffset = offset;
      await expect(sheet(page)).toHaveAttribute("data-dragging", "true");
      await expectSheetAndMapHeights(page, bounds.height - offset, 844 - bounds.height + offset);
      await expectMapToFillRegion(page);
      await expect
        .poll(async () => {
          await page.clock.runFor(32);
          return sheet(page).evaluate((element) => {
            const chatTop = Number.parseFloat(document.documentElement.style.getPropertyValue("--board-chat-top"));
            return Math.abs(chatTop - (element.getBoundingClientRect().top - 52));
          });
        })
        .toBeLessThanOrEqual(1);
      expect(await naverMap.snapshot()).toEqual(originalView);
    }

    await page.mouse.up();
    await page.clock.runFor(400);
    await expect(sheet(page)).toHaveAttribute("data-dragging", "false");
    await expectMapToFillRegion(page);
    expect(await mapSetSizeCount(page)).toBe(originalSetSizeCount);
  });

  test(`최소·70%·전체 시트와 화면 크기 변경에도 지도가 영역을 채우고 시점을 유지한다 (${reducedMotion})`, async ({
    page,
    naverMap,
  }) => {
    await page.emulateMedia({ reducedMotion });
    await openMap(page);
    await naverMap.setZoom(14);
    await naverMap.panToStop(11);
    const originalView = await naverMap.snapshot();
    await expectMapToFillRegion(page);
    const originalSetSizeCount = await mapSetSizeCount(page);

    await handle(page).press("Home");
    await page.clock.runFor(400);
    await expect(sheet(page)).toHaveAttribute("data-snap", "minimum");
    await expect.poll(() => height(map(page))).toBeGreaterThan(422);
    await expectMapToFillRegion(page);
    expect(await mapSetSizeCount(page)).toBe(originalSetSizeCount);
    expect(await naverMap.snapshot()).toEqual(originalView);

    await handle(page).press("End");
    await page.clock.runFor(400);
    await expect(map(page)).toHaveCount(0);
    await handle(page).press("Home");
    await page.clock.runFor(400);
    await expect.poll(() => height(map(page))).toBeGreaterThan(422);
    await expectMapToFillRegion(page);
    expect(await mapSetSizeCount(page)).toBe(originalSetSizeCount);
    expect(await naverMap.snapshot()).toEqual(originalView);

    await handle(page).press("ArrowUp");
    await handle(page).press("ArrowUp");
    await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
    await expectSheetAndMapHeights(page, 844 * 0.7, 844 * 0.3);
    await expectMapToFillRegion(page);
    expect(await mapSetSizeCount(page)).toBe(originalSetSizeCount);
    expect(await naverMap.snapshot()).toEqual(originalView);

    await page.setViewportSize({ width: 360, height: 900 });
    await expect(sheet(page)).toHaveAttribute("data-snap", "expanded");
    await expectSheetAndMapHeights(page, 630, 270);
    await expectMapToFillRegion(page);
    await expect.poll(() => mapSetSizeCount(page)).toBeGreaterThan(originalSetSizeCount);
    expect(await naverMap.snapshot()).toEqual(originalView);
  });
}

test("반대 방향 지도 정류장은 탭·시트·펼침·목록 스크롤을 함께 바꾸고 재선택해도 접히지 않는다", async ({
  page,
  naverMap,
}, testInfo) => {
  await openMap(page);
  await handle(page).press("Home");
  await naverMap.setZoom(14);
  await naverMap.panToStop(23);
  const pin = map(page).locator("button[data-map-stop-sequence='23']");
  await expect(pin).toHaveAccessibleName(`${targetStopNames.DOWN} ${directionLabels.DOWN} 정류장 지도에서 선택`);
  await pin.click();
  await page.clock.runFor(32);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expect(tab(page, "DOWN")).toHaveAttribute("aria-pressed", "true");
  const row = list(page).locator("li[data-stop-sequence='23']");
  await expect(row.locator("button[aria-expanded]")).toHaveAttribute("aria-expanded", "true");
  await expect(row).toHaveAttribute("data-selected", "true");
  await expect(pin).toHaveAttribute("aria-pressed", "true");
  expect(await pin.evaluate((element) => element.getBoundingClientRect().width)).toBe(32);
  expect((await naverMap.snapshot()).zoom).toBe(15);
  await expect.poll(() => list(page).evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await pin.click();
  await page.clock.runFor(32);
  await expect(row.locator("button[aria-expanded]")).toHaveAttribute("aria-expanded", "true");
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-selected.png" });
});

test("목록 선택은 지도에 반영하고 직접 방향 전환은 선택·스크롤만 초기화한다", async ({ page, naverMap }) => {
  await openMap(page);
  const row = list(page).locator("li[data-stop-sequence='11']");
  const stop = row.locator("button[aria-expanded]");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();
  await page.clock.runFor(32);
  await expect(stop).toHaveAttribute("aria-expanded", "true");
  const pin = map(page).locator("button[data-map-stop-sequence='11']");
  await expect(pin).toHaveAccessibleName(`${targetStopNames.UP} ${directionLabels.UP} 정류장 지도에서 선택`);
  await expect(pin).toHaveAttribute("aria-pressed", "true");
  expect((await naverMap.snapshot()).zoom).toBe(15);
  const focused = await naverMap.snapshot();
  await tab(page, "DOWN").click();
  await page.clock.runFor(32);
  await expect(list(page).locator("[data-selected='true']")).toHaveCount(0);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBe(0);
  expect(await naverMap.snapshot()).toEqual(focused);
  await expect(map(page).getByRole("button", { name: /부근 버스/ })).toHaveCount(1);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
});

for (const reducedMotion of ["reduce", "no-preference"] as const) {
  for (const sequence of [6, 12]) {
    test(`목록에서 ${sequence}번 정류장을 펼치고 접어도 클릭한 위치를 유지한다 (${reducedMotion})`, async ({
      page,
    }) => {
      await page.emulateMedia({ reducedMotion });
      await openMap(page);
      const stop = list(page).locator(`li[data-stop-sequence='${sequence}'] button[aria-expanded]`);
      await stop.evaluate((element) => {
        const container = element.closest("section");
        if (container === null) throw new Error("정류장 목록이 없습니다");
        container.scrollTop +=
          element.getBoundingClientRect().top - container.getBoundingClientRect().top - container.clientHeight / 2;
      });
      await page.clock.runFor(32);
      const originalTop = await stop.evaluate((element) => element.getBoundingClientRect().top);
      const originalScroll = await list(page).evaluate((element) => element.scrollTop);

      await stop.click({ position: { x: 30, y: 20 } });
      await page.clock.runFor(400);
      await expect(stop).toHaveAttribute("aria-expanded", "true");
      expect(await stop.evaluate((element) => element.getBoundingClientRect().top)).toBeCloseTo(originalTop, 0);
      expect(await list(page).evaluate((element) => element.scrollTop)).toBeCloseTo(originalScroll, 0);

      // 키보드로 접고 다시 펼쳐도 같은 버튼에 포커스가 남는다.
      await stop.press("Enter");
      await page.clock.runFor(400);
      await expect(stop).toHaveAttribute("aria-expanded", "false");
      await expect(stop).toBeFocused();
      await stop.press("Enter");
      await page.clock.runFor(400);
      await expect(stop).toHaveAttribute("aria-expanded", "true");
      await expect(stop).toBeFocused();
      expect(await stop.evaluate((element) => element.getBoundingClientRect().top)).toBeCloseTo(originalTop, 0);
    });
  }
}

test("지도 선택 직후 목록에서 펼치면 대기 중인 지도 선택의 목록 이동을 취소한다", async ({ page, naverMap }) => {
  await openMap(page);
  await naverMap.setZoom(14);
  await naverMap.panToStop(11);
  await map(page).locator("button[data-map-stop-sequence='11']").click();
  const stop = list(page).locator("li[data-stop-sequence='6'] button[aria-expanded]");
  await stop.evaluate((element) => {
    const container = element.closest("section");
    if (container === null) throw new Error("정류장 목록이 없습니다");
    container.scrollTop +=
      element.getBoundingClientRect().top - container.getBoundingClientRect().top - container.clientHeight / 2;
  });
  const originalTop = await stop.evaluate((element) => element.getBoundingClientRect().top);

  await stop.click({ position: { x: 30, y: 20 } });
  await page.clock.runFor(400);
  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(list(page).locator("li[data-stop-sequence='11'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  expect(await stop.evaluate((element) => element.getBoundingClientRect().top)).toBeCloseTo(originalTop, 0);
});

test("위쪽 정류장이 접힐 때 새로 펼친 정류장을 목록 상단으로 이동시키지 않는다", async ({ page }) => {
  await openMap(page);
  const previous = list(page).locator("li[data-stop-sequence='6'] button[aria-expanded]");
  await previous.scrollIntoViewIfNeeded();
  await previous.click();
  await page.clock.runFor(32);
  const next = list(page).locator("li[data-stop-sequence='7'] button[aria-expanded]");
  await next.scrollIntoViewIfNeeded();
  await page.clock.runFor(32);
  const previousRowHeight = await list(page)
    .locator("li[data-stop-sequence='6']")
    .evaluate((element) => element.getBoundingClientRect().height);
  const originalTop = await next.evaluate((element) => element.getBoundingClientRect().top);
  await next.click({ position: { x: 30, y: 20 } });
  await page.clock.runFor(32);
  await expect(previous).toHaveAttribute("aria-expanded", "false");
  await expect(next).toHaveAttribute("aria-expanded", "true");
  const collapsedRowHeight = await list(page)
    .locator("li[data-stop-sequence='6']")
    .evaluate((element) => element.getBoundingClientRect().height);
  // 위쪽 행이 접힌 높이만큼의 자연스러운 이동만 허용한다.
  const nextTop = await next.evaluate((element) => element.getBoundingClientRect().top);
  expect(nextTop).toBeGreaterThanOrEqual(originalTop - (previousRowHeight - collapsedRowHeight) - 1);
  expect(nextTop).toBeLessThanOrEqual(originalTop + 1);
});

test("현재 위치 성공은 작은 반투명 원과 파란 점을 표시하고 권한 거부 시 안내한다", async ({ page, naverMap }) => {
  await openMap(page);
  await page.evaluate(() =>
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: {
        getCurrentPosition(success: PositionCallback) {
          success({
            coords: {
              latitude: 37.43,
              longitude: 127.16,
              accuracy: 24,
              altitude: null,
              altitudeAccuracy: null,
              heading: null,
              speed: null,
              toJSON: () => ({}),
            },
            timestamp: Date.now(),
            toJSON: () => ({}),
          });
        },
      },
    }),
  );
  await page.getByRole("button", { name: "현재 위치 보기", exact: true }).click();
  await expect(map(page).getByRole("img", { name: "내 현재 위치", exact: true })).toBeVisible();
  await expect(map(page).getByTestId("location-accuracy")).toHaveCount(0);
  expect(await naverMap.snapshot()).toEqual({ zoom: 15, latitude: 37.43, longitude: 127.16 });
  const located = await naverMap.snapshot();

  await page.evaluate(() =>
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: {
        getCurrentPosition(_success: PositionCallback, failure: PositionErrorCallback) {
          failure({ code: 1, message: "denied", PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
        },
      },
    }),
  );
  await page.getByRole("button", { name: "현재 위치 보기", exact: true }).click();
  await expect(map(page).getByText("위치 권한을 허용하면 내 위치를 볼 수 있어요", { exact: true })).toBeVisible();
  expect(await naverMap.snapshot()).toEqual(located);
});

for (const failure of [
  { source: "auth", name: "SDK 인증 실패가 늦게 전달돼도" },
  { source: "update", name: "지도 갱신 중 SDK 오류가 발생해도" },
  { source: "resize", name: "지도 크기 변경 중 SDK 오류가 발생해도" },
  { source: "frame", name: "차량 이동 중 SDK 오류가 발생해도" },
] as const) {
  test(`${failure.name} 정류장 목록과 방향 선택을 계속 사용할 수 있다`, async ({ page, naverMap }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    if (failure.source === "frame") await page.emulateMedia({ reducedMotion: "no-preference" });
    await openMap(page);
    await naverMap.failNative(failure.source === "auth" ? "all" : failure.source);

    switch (failure.source) {
      case "auth":
        await page.evaluate(() => {
          if (typeof window.navermap_authFailure !== "function") throw new Error("인증 실패 핸들러가 없습니다");
          window.navermap_authFailure();
        });
        break;
      case "update":
        await tab(page, "DOWN").click();
        break;
      case "resize":
        await page.setViewportSize({ width: 390, height: 900 });
        break;
      case "frame":
        await page.clock.runFor(32);
        break;
    }
    await expect(map(page).getByText("지도를 불러오지 못했어요", { exact: true })).toBeVisible();
    await expect(map(page).getByText("정류장 목록은 계속 확인할 수 있어요", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toHaveCount(0);
    await expect(map(page).getByTestId("naver-map-test-double")).toHaveCount(0);
    await expect(sheet(page)).toHaveAttribute("data-snap", "half");

    await tab(page, "DOWN").click();
    await expect(tab(page, "DOWN")).toHaveAttribute("aria-pressed", "true");
    const downStop = list(page).locator("li[data-stop-sequence='23'] button[aria-expanded]");
    await downStop.scrollIntoViewIfNeeded();
    await downStop.click();
    await page.clock.runFor(32);
    await expect(downStop).toHaveAttribute("aria-expanded", "true");
    await tab(page, "UP").click();
    await expect(tab(page, "UP")).toHaveAttribute("aria-pressed", "true");
    await expect(list(page).locator("li[data-stop-sequence='11']")).toContainText(targetStopNames.UP);
    await page.clock.runFor(32);
    expect(pageErrors).toEqual([]);
  });
}

test("노선 버전 갱신 시 이전 순번의 선택을 새 정류장에 이어 붙이지 않고 절반 높이로 초기화한다", async ({
  page,
  api,
}) => {
  await openMap(page);
  const stop = list(page).locator("li[data-stop-sequence='11'] button[aria-expanded]");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();
  await page.clock.runFor(32);
  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await handle(page).press("End");
  await expect(sheet(page)).toHaveAttribute("data-snap", "full");

  const replacedStop = api.data.board.stops.find((item) => item.direction === "UP" && item.sequence === 11);
  if (replacedStop === undefined) throw new Error("갱신할 상행 정류장이 없습니다");
  replacedStop.stopId = "replacement-stop";
  replacedStop.name = "새 버전 중앙역";
  api.data.board.route.referenceVersionId = "2";
  api.data.board.observedAt = "2026-09-16T08:01:00+09:00";
  api.data.board.staleAt = "2026-09-16T08:06:00+09:00";
  api.data.vehicles.referenceVersionId = "2";
  api.data.vehicles.observation.observedAt = api.data.board.observedAt;
  api.data.vehicles.observation.staleAt = api.data.board.staleAt;
  const refreshedBoard = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.board && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await (await refreshedBoard).finished();
  await page.clock.runFor(32);

  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expect(map(page)).toBeVisible();
  await expect(list(page).locator("[data-selected='true']")).toHaveCount(0);
  await expect(list(page).locator("button[aria-expanded='true']")).toHaveCount(0);
  await expect(list(page).locator("li[data-stop-sequence='11']")).toContainText("새 버전 중앙역");
  expect(await list(page).evaluate((element) => element.scrollTop)).toBe(0);
});

test("선택 방향이 운행 전이어도 반대 방향은 운행 종료 안내의 버튼으로 확인할 수 있다", async ({ page, api }) => {
  const up = api.data.board.route.directions.find((direction) => direction.id === "UP");
  if (up === undefined) throw new Error("상행 운행 정보가 없습니다");
  up.firstDepartureTime = "09:00";
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).toBeVisible();
  await expect(sheet(page)).toHaveCount(0);
  await expect(map(page)).toHaveCount(0);

  await page.getByRole("button", { name: `${directionLabels.DOWN} 보기`, exact: true }).click();
  await page.clock.runFor(32);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  await expect(map(page)).toBeVisible();
  await expect(tab(page, "DOWN")).toHaveAttribute("aria-pressed", "true");
  await expect(list(page).locator("li[data-stop-sequence='23']")).toContainText(targetStopNames.DOWN);
  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).toHaveCount(0);
});

test("운행 종료 화면에는 지도와 바텀시트가 없다", async ({ page, api }) => {
  api.data.board.observedAt = "2026-09-16T23:30:00+09:00";
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).toBeVisible();
  await expect(sheet(page)).toHaveCount(0);
  await expect(map(page)).toHaveCount(0);
  await expect(handle(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: /방면 보기$/ })).toHaveCount(0);
});
