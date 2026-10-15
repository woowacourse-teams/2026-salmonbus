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
  await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
  await page.clock.runFor(32);
}

async function height(locator: Locator) {
  return locator.evaluate((element) => element.getBoundingClientRect().height);
}

async function dragHeader(page: Page, destinationY: number) {
  const bounds = await sheet(page).boundingBox();
  if (bounds === null) throw new Error("드래그할 시트가 없습니다");
  await page.mouse.move(bounds.x + 25, bounds.y + 12);
  await page.mouse.down();
  await page.mouse.move(bounds.x + 25, destinationY, { steps: 12 });
  await page.clock.runFor(32);
  await page.mouse.up();
  await page.clock.runFor(32);
}

test("첫 화면은 정확히 절반 높이이고 줌에 따라 정류장만 숨기며 차량·방향 화살표는 유지한다", async ({
  page,
  naverMap,
}, testInfo) => {
  await openMap(page);
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
  expect(await height(sheet(page))).toBeCloseTo(422, 0);
  expect(await height(map(page))).toBeCloseTo(422, 0);
  await expect(sheet(page).locator("[data-selected='true']")).toHaveCount(0);
  await expect(map(page).locator("button[data-map-stop-sequence]:visible")).toHaveCount(0);
  await expect(map(page).getByRole("img", { name: /부근 버스/ })).toHaveCount(2);
  await expect(map(page).getByTestId("naver-polyline")).toHaveCount(2);
  expect(await map(page).locator("[data-marker-kind='arrow']").count()).toBeGreaterThan(0);
  await expect(sheet(page).getByText("15–25분", { exact: true })).toBeVisible();
  await expect(sheet(page).getByText("3대", { exact: true })).toBeVisible();
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-half.png" });

  await naverMap.setZoom(15);
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
  await naverMap.setZoom(14);
  await expect(map(page).locator("button[data-map-stop-sequence]:visible")).toHaveCount(0);
  await expect(map(page).getByRole("img", { name: /부근 버스/ })).toHaveCount(2);
});

test("키보드로 최소·절반·전체를 선택하고 목록 스크롤은 시트 높이를 바꾸지 않는다", async ({ page }, testInfo) => {
  await openMap(page);
  await handle(page).focus();
  await handle(page).press("Home");
  await expect(sheet(page)).toHaveAttribute("data-snap", "minimum");
  await expect(list(page)).toHaveCount(0);
  await expect(tab(page, "UP")).toBeVisible();
  await expect(sheet(page).getByText("운행 시간", { exact: true })).toBeVisible();
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

  await handle(page).press("End");
  await expect(sheet(page)).toHaveAttribute("data-snap", "full");
  expect(await height(sheet(page))).toBeCloseTo(844, 0);
  await expect(map(page)).toHaveCount(0);
  await expect(sheet(page).getByRole("link", { name: "노선 선택으로 돌아가기" })).toBeVisible();
  if (testInfo.project.name === "chromium") await page.screenshot({ path: "/tmp/sal192-board-full.png" });
  await handle(page).press("ArrowDown");
  await expect(sheet(page)).toHaveAttribute("data-snap", "half");
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

test("반대 방향 지도 정류장은 탭·시트·펼침·목록 스크롤을 함께 바꾸고 재선택해도 접히지 않는다", async ({
  page,
  naverMap,
}, testInfo) => {
  await openMap(page);
  await handle(page).press("Home");
  await naverMap.setZoom(15);
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
  expect((await naverMap.snapshot()).zoom).toBe(16);
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
  expect((await naverMap.snapshot()).zoom).toBe(16);
  const focused = await naverMap.snapshot();
  await tab(page, "DOWN").click();
  await page.clock.runFor(32);
  await expect(list(page).locator("[data-selected='true']")).toHaveCount(0);
  expect(await list(page).evaluate((element) => element.scrollTop)).toBe(0);
  expect(await naverMap.snapshot()).toEqual(focused);
  await expect(map(page).getByRole("img", { name: /부근 버스/ })).toHaveCount(1);
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
  await naverMap.setZoom(15);
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

test("현재 위치 성공은 파란 위치·정확도 범위를 표시하고 권한 거부는 지도를 유지하며 안내한다", async ({
  page,
  naverMap,
}) => {
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
  await expect(map(page).getByTestId("location-accuracy")).toBeVisible();
  expect(await naverMap.snapshot()).toEqual({ zoom: 16, latitude: 37.43, longitude: 127.16 });
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
