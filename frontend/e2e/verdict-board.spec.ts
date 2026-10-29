import type { Page } from "@playwright/test";
import { apiPaths, pollIntervalMs, targetStopNames } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import { selectRoute } from "./utils/route-select";
import { directionButton, expectDirection, openBoard, targetRow, targetStop } from "./utils/verdict-board";
import { forecastFixtures } from "../src/testing/forecastFixtures";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
});

test("watch 배차간격을 표시하고 다음 응답에 정보가 없으면 안내 문구로 바뀐다", async ({ page, api }) => {
  await openBoard(page);
  const summary = page.getByRole("definition").filter({ hasText: "15~25분" });
  await expect(summary).toBeVisible();

  api.data.watch.route.headways = { weekday: null, saturday: null, sunday: null, holiday: null };
  const refreshed = page.waitForResponse(apiPaths.watch);
  await page.clock.fastForward(pollIntervalMs);
  await (await refreshed).finished();
  await expect(page.getByText("정보 준비 중", { exact: true })).toBeVisible();
  await expect(page.getByText("15~25분", { exact: true })).toHaveCount(0);

  delete api.data.watch.route.headways;
  const legacyResponse = page.waitForResponse(apiPaths.watch);
  await page.clock.fastForward(pollIntervalMs);
  await (await legacyResponse).finished();
  await expect(page.getByText("정보 준비 중", { exact: true })).toBeVisible();
});

test("화면을 켜 둔 채 한국 시간 자정을 넘기면 새 응답 없이도 오늘의 배차간격으로 바뀐다", async ({ page, api }) => {
  await page.clock.setSystemTime(new Date("2026-10-30T23:59:30+09:00"));
  await openBoard(page);
  await expect(page.getByText("15~25분", { exact: true })).toBeVisible();

  api.mode.watch = "network-error";
  await page.clock.runFor(60_000);
  await expect(page.getByText("20~30분", { exact: true })).toBeVisible();
});

test("상행에서 하행으로 전환하면 하행 정류장만 순서대로 표시된다", async ({ page }) => {
  await openBoard(page);
  await directionButton(page, "UP").click();
  await expectDirection(page, "UP");

  await directionButton(page, "DOWN").click();
  await expectDirection(page, "DOWN");
});

test("판정 보드 최초 조회 실패는 운행 없음이나 예측 없음과 구분된다", async ({ page, api }) => {
  api.mode.watch = "network-error";
  await page.goto("/");
  await selectRoute(page);

  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).toBeVisible();
  await expect(page.locator("button[aria-expanded]")).toHaveCount(0);
  await expect(page.getByText(/도착 시 .*석 예상해요/)).toHaveCount(0);
  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).not.toBeVisible();
  await expect(page.getByText("지금 오는 차량이 없어요", { exact: true })).not.toBeVisible();
  await expect(page.getByText("현재 예측할 수 있는 차량이 없습니다", { exact: true })).not.toBeVisible();
});

test("판정 보드 최초 조회가 503이면 Retry-After 뒤 다시 불러와 판정 보드를 보여 준다", async ({ page, api }) => {
  api.mode.watch = { status: 503, retryAfterSeconds: 30 };
  await page.goto("/");
  await selectRoute(page);
  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).toBeVisible();

  api.mode.watch = "success";
  const retried = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.watch && response.status() === 200,
  );
  // Retry-After를 읽지 못하면 60초 뒤에 다시 불러오므로 30초 안에는 응답이 오지 않는다.
  await page.clock.fastForward(30_000);
  await retried;

  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).toBeHidden();
  expect(api.requests.watch).toBe(2);
});

test("유효기간 안의 자동 갱신 실패 시 마지막 예측을 유지한다 (현재 구현)", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();

  const arrivals = targetRow(page, "UP").getByRole("listitem");
  await expect(arrivals).toHaveText([
    /(?:\d+분 남음|1분 이내)\s*3정류장 전\s*도착 시 5석 예상해요/,
    /(?:\d+분 남음|1분 이내)\s*7정류장 전\s*도착 시 2석 예상해요/,
  ]);
  const previousForecast = await arrivals.allTextContents();
  const requestsBeforePoll = api.requests.watch;
  api.mode.watch = "network-error";

  // 단순히 화면이 남았는지만 보지 않고 후속 폴링의 실제 네트워크 실패를 기다린다.
  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.watch,
  );
  await page.clock.fastForward(pollIntervalMs);
  await failedRequest;
  await page.clock.runFor(1);
  expect(api.requests.watch).toBeGreaterThan(requestsBeforePoll);
  expect(await page.evaluate(() => Date.now())).toBeLessThan(Date.parse(api.data.watch.staleAt));

  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(arrivals).toHaveText(previousForecast);
  await expect(arrivals.nth(0)).toBeVisible();
  await expect(arrivals.nth(1)).toBeVisible();
  // 배너 유무는 미확정 정책이므로 고정하지 않고 예측 화면이 유지되는지만 확인한다.
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
});

test("자동 갱신 실패 후 다음 요청이 성공하면 새 예측으로 교체한다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();

  const arrivals = targetRow(page, "UP").getByRole("listitem");
  await expect(arrivals).toHaveText([
    /(?:\d+분 남음|1분 이내)\s*3정류장 전\s*도착 시 5석 예상해요/,
    /(?:\d+분 남음|1분 이내)\s*7정류장 전\s*도착 시 2석 예상해요/,
  ]);
  const requestsBeforeFailure = api.requests.watch;
  api.mode.watch = "network-error";

  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.watch,
  );
  await page.clock.fastForward(pollIntervalMs);
  await failedRequest;
  await page.clock.runFor(1);
  expect(api.requests.watch).toBeGreaterThan(requestsBeforeFailure);
  expect(await page.evaluate(() => Date.now())).toBeLessThan(Date.parse(api.data.watch.staleAt));

  // 실패 직후 기존 예측의 유지 여부는 별도 테스트에서 검증한다.
  // 다음 성공 응답이 반영됐는지 구분하도록 예측 값과 항목 수를 바꾼다.
  const stopData = api.data.watch.stops.find((stop) => stop.name === targetStopNames.UP);
  if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
  stopData.approachingVehicles = [
    {
      vehicleId: "bus-1",
      remainingStops: 2,
      forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9, expectedSeats: 9 },
    },
  ];
  api.mode.watch = "success";
  const requestsBeforeRecovery = api.requests.watch;

  const recoveredResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.watch && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await (await recoveredResponse).finished();
  expect(api.requests.watch).toBeGreaterThan(requestsBeforeRecovery);
  expect(await page.evaluate(() => Date.now())).toBeLessThan(Date.parse(api.data.watch.staleAt));

  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(arrivals).toHaveText([/(?:\d+분 남음|1분 이내)\s*2정류장 전\s*도착 시 9석 예상해요/]);
  await expect(arrivals.nth(0)).toBeVisible();
});

test("화면을 켜 둔 채 연결이 돌아오면 다음 폴링을 기다리지 않고 판정 보드를 다시 불러온다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();

  const arrivals = targetRow(page, "UP").getByRole("listitem");
  await expect(arrivals).toHaveText([
    /(?:\d+분 남음|1분 이내)\s*3정류장 전\s*도착 시 5석 예상해요/,
    /(?:\d+분 남음|1분 이내)\s*7정류장 전\s*도착 시 2석 예상해요/,
  ]);

  // 오프라인 요청이 mock까지 와도 실패하도록 응답도 실패로 맞춘다.
  await page.context().setOffline(true);
  api.mode.watch = "network-error";
  api.mode.arrivals = "network-error";
  const pollFailed = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.watch,
  );
  await page.clock.fastForward(pollIntervalMs);
  await pollFailed;
  await page.clock.runFor(1_000);

  const stopData = api.data.watch.stops.find((stop) => stop.name === targetStopNames.UP);
  if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
  stopData.approachingVehicles = [
    {
      vehicleId: "bus-1",
      remainingStops: 2,
      forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9, expectedSeats: 9 },
    },
  ];
  api.mode.watch = "success";

  // 시계가 멈춰 있어서 60초 뒤 예약 요청은 실행될 수 없다. 여기서 오는 응답은 online이 시작한 요청이다.
  const refilled = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.watch && response.status() === 200,
  );
  await page.context().setOffline(false);
  await refilled;

  // ETA가 복구되지 않아도 watch의 거리와 좌석은 먼저 갱신한다.
  await expect(arrivals).toHaveText([/2정류장 전\s*도착 시 9석 예상해요/]);
  api.mode.arrivals = "success";
  await targetStop(page, "UP").click();
  await targetStop(page, "UP").click();
  await expect(arrivals).toHaveText([/(?:\d+분 남음|1분 이내)\s*2정류장 전\s*도착 시 9석 예상해요/]);
});

test("화면이 숨은 동안에는 연결이 돌아와도 요청하지 않고 다시 보일 때 한 번만 불러온다", async ({ page }) => {
  await openBoard(page);
  await setVisibility(page, "hidden");
  await page.context().setOffline(true);

  // 페이지가 요청을 보내는 순간에 세므로, 화면 복귀 요청이 앞 요청을 취소해도 횟수에 남는다.
  const issuedRequests: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path === apiPaths.watch) issuedRequests.push(path);
  });

  // 리스너를 먼저 붙여 두어야 online 이벤트를 놓치지 않는다. 객체로 감싸 Promise를 기다리지 않고 핸들만 받는다.
  const onlineListener = await page.evaluateHandle(() => ({
    delivered: new Promise<void>((resolve) => window.addEventListener("online", () => resolve(), { once: true })),
  }));
  await page.context().setOffline(false);
  await onlineListener.evaluate((listener) => listener.delivered);

  const reloaded = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.watch && response.status() === 200,
  );
  await setVisibility(page, "visible");
  await reloaded;

  expect(issuedRequests).toHaveLength(1);
});

// Playwright는 탭 숨김을 직접 지원하지 않아서, 훅이 읽는 값을 덮어쓰고 이벤트를 보낸다.
async function setVisibility(page: Page, visibility: DocumentVisibilityState) {
  await page.evaluate((state) => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
    document.dispatchEvent(new Event("visibilitychange"));
  }, visibility);
}

function busMarkerAt(page: Page, stopName: string) {
  return page
    .getByRole("region", { name: "판정보드 정류장 목록", exact: true })
    .getByRole("button", { name: new RegExp(`^${stopName} 정류장,`) });
}

async function pollRouteWatch(page: Page) {
  const polled = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.watch && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await polled;
  await settleMarkers(page);
}

// 픽스처가 시계를 멈춰 두어서, 마커 위치를 재는 타이머가 돌도록 시계를 조금 진행한다.
async function settleMarkers(page: Page) {
  await page.clock.runFor(100);
}

test("watch가 먼저 갱신돼도 지도와 펼친 목록의 거리가 함께 바뀐다", async ({ page, api }) => {
  api.cacheMaxAgeSeconds = 15;
  // 정류장 8에 있는 버스라서 목표 정류장(순번 11)까지 3정류장 전이다. 백엔드 remainingStops 계산과 같다.
  const bus = api.data.watch.vehicles.find((vehicle) => vehicle.vehicleId === "bus-1");
  if (bus === undefined) throw new Error("bus-1 차량 fixture가 필요합니다");
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();
  await settleMarkers(page);
  const nearestArrival = targetRow(page, "UP").getByRole("listitem").nth(0);
  await expect(nearestArrival).toHaveText(/(?:\d+분 남음|1분 이내)\s*3정류장 전\s*도착 시 5석 예상해요/);
  await expect(busMarkerAt(page, "상행 정류장 8")).toBeVisible();

  const target = api.data.watch.stops.find((candidate) => candidate.name === targetStopNames.UP);
  const forecastedBus = target?.approachingVehicles.find((vehicle) => vehicle.vehicleId === "bus-1");
  if (forecastedBus === undefined) throw new Error("상행 목표 정류장의 bus-1 예보 fixture가 필요합니다");
  Object.assign(bus, { currentStopSequence: 9, stopId: "1009", stopName: "상행 정류장 9" });
  Object.assign(forecastedBus, {
    remainingStops: 2,
    forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 4 },
  });
  api.data.watch.observedAt = "2026-09-16T08:00:15+09:00";
  const watched = page.waitForResponse((response) => new URL(response.url()).pathname === apiPaths.watch);
  await page.clock.fastForward(15_000);
  await watched;
  await settleMarkers(page);

  expect(api.requests.arrivals).toBe(1);
  await expect(busMarkerAt(page, "상행 정류장 9")).toBeVisible();
  await expect(busMarkerAt(page, "상행 정류장 8")).toHaveCount(0);
  await expect(nearestArrival).toHaveText(/(?:\d+분 남음|1분 이내)\s*2정류장 전\s*도착 시 4석 예상해요/);
});

test("서버가 맨 앞에 보낸 목표 정류장 도착 버스는 그 순서대로 정류장 도착으로 보이고 시간 칸이 비어 있다", async ({
  page,
  api,
}) => {
  const target = api.data.watch.stops.find((stop) => stop.name === targetStopNames.UP);
  const previous = api.data.watch.stops.find(
    (stop) => stop.direction === target?.direction && stop.sequence === (target?.sequence ?? 0) - 1,
  );
  if (target === undefined || previous === undefined)
    throw new Error("상행 목표 정류장과 앞 정류장 fixture가 필요합니다");
  for (const [vehicleId, stop] of [
    ["bus-1", target],
    ["bus-2", previous],
  ] as const) {
    const bus = api.data.watch.vehicles.find((vehicle) => vehicle.vehicleId === vehicleId);
    if (bus === undefined) throw new Error(`${vehicleId} 차량 fixture가 필요합니다`);
    Object.assign(bus, { currentStopSequence: stop.sequence, stopId: stop.stopId, stopName: stop.name });
  }
  // 두 버스 모두 정류장에 서 있다. 앞 정류장에 선 버스는 1정류장 전이다.
  target.approachingVehicles = [
    {
      vehicleId: "bus-1",
      remainingStops: 0,
      forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 5 },
    },
    {
      vehicleId: "bus-2",
      remainingStops: 1,
      forecast: { status: "AVAILABLE", seatAvailableProbability: 0.5, expectedSeats: 2 },
    },
  ];

  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();

  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /^정류장 도착\s*도착 시 5석 예상해요$/,
    /(?:\d+분 남음|1분 이내)\s*1정류장 전\s*도착 시 2석 예상해요/,
  ]);
});

test("판의 만료 시각이 지나면 차량 마커만 숨기고, 새 관측을 받으면 다시 표시한다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();
  await settleMarkers(page);
  const nearestArrival = targetRow(page, "UP").getByRole("listitem").nth(0);
  await expect(busMarkerAt(page, "상행 정류장 8")).toBeVisible();

  // 갱신이 계속 실패해서 마지막으로 받은 응답의 staleAt을 넘긴다.
  api.mode.watch = "network-error";
  const now = await page.evaluate(() => Date.now());
  await page.clock.fastForward(Date.parse(api.data.watch.staleAt) - now + 1_000);
  await settleMarkers(page);

  await expect(busMarkerAt(page, "상행 정류장 8")).toHaveCount(0);
  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(nearestArrival).toHaveText(/(?:\d+분 남음|1분 이내)\s*3정류장 전\s*도착 시 5석 예상해요/);

  // 만료 기록은 이전 관측에만 남으므로 새 관측의 차량은 다시 보여야 한다.
  const bus = api.data.watch.vehicles.find((vehicle) => vehicle.vehicleId === "bus-1");
  if (bus === undefined) throw new Error("bus-1 차량 fixture가 필요합니다");
  Object.assign(bus, { currentStopSequence: 9, stopId: "1009", stopName: "상행 정류장 9" });
  api.data.watch.observedAt = "2026-09-16T08:05:00+09:00";
  api.data.watch.staleAt = "2026-09-16T08:10:00+09:00";
  api.mode.watch = "success";
  await pollRouteWatch(page);

  await expect(busMarkerAt(page, "상행 정류장 9")).toBeVisible();
});

test("운행 중인 차량이 없으면 운행 없음 안내를 표시한다", async ({ page, api }) => {
  api.data.watch.vehiclesInService = 0;
  api.data.watch.vehicles = [];
  await openBoard(page);

  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).toBeVisible();
  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).not.toBeVisible();
  await expect(page.locator("button[aria-expanded]")).toHaveCount(0);
  await expect(page.getByText(/도착 시 .*석 예상해요/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: /방면 보기$/ })).toHaveCount(0);
});

test("운행 중인 노선의 특정 정류장에 접근 차량이 없으면 차량 없음 안내를 표시한다", async ({ page, api }) => {
  const stopData = api.data.watch.stops.find((stop) => stop.name === targetStopNames.UP);
  if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
  stopData.approachingVehicles = [];
  await openBoard(page);

  const stop = targetStop(page, "UP");
  const row = targetRow(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await expect(row.getByText("지금 오는 차량이 없어요", { exact: true })).toBeVisible();
  await stop.click();
  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(row.getByText("현재 예측할 수 있는 차량이 없습니다", { exact: true })).toBeVisible();
  await expect(row.getByRole("listitem")).toHaveCount(0);
  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).not.toBeVisible();
  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).not.toBeVisible();
});

for (const { name, vehicle, chip, seatLabel } of [
  {
    name: "예측값이 있으면 좌석 수와 탑승 확률을 표시한다",
    vehicle: forecastFixtures.available,
    chip: "탑승 확률 높음",
    seatLabel: "도착 시 5석 예상해요",
  },
  {
    name: "예상 좌석만 생략되면 탑승 확률을 유지한다",
    vehicle: forecastFixtures.withoutExpectedSeats,
    chip: "탑승 확률 높음",
    seatLabel: "예상 좌석 정보 없음",
  },
  {
    name: "확률과 예상 좌석이 0이면 빈자리 없음으로 표시한다",
    vehicle: forecastFixtures.zeroSeats,
    chip: "탑승 확률 매우 낮음",
    seatLabel: "도착 시 빈자리가 없어요",
  },
  {
    name: "현재 잔여석을 알아도 도착 예측이 없으면 예측 불가로 표시한다",
    vehicle: forecastFixtures.unavailable,
    chip: "좌석 예측 정보가 없어요",
    seatLabel: "예상 좌석 정보 없음",
  },
]) {
  test(name, async ({ page, api }) => {
    const stopData = api.data.watch.stops.find((stop) => stop.name === targetStopNames.UP);
    if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
    stopData.approachingVehicles = [{ ...vehicle, vehicleId: "bus-1", remainingStops: 3 }];

    // 같은 차량의 현재 좌석이 도착 예측을 대신하지 않는지 확인한다.
    const liveVehicle = api.data.watch.vehicles.find((vehicle) => vehicle.vehicleId === "bus-1");
    if (liveVehicle === undefined) throw new Error("bus-1 실시간 차량 fixture가 필요합니다");
    liveVehicle.seat = { kind: "EXACT", remaining: 28 };

    await openBoard(page);
    const stop = targetStop(page, "UP");
    const row = targetRow(page, "UP");
    await stop.scrollIntoViewIfNeeded();
    await expect(row.getByText(chip, { exact: true })).toBeVisible();
    await stop.click();

    await expect(row.getByRole("listitem")).toHaveText([`2분 남음3정류장 전${seatLabel}`]);
    await expect(row.getByText("지금 오는 차량이 없어요", { exact: true })).toHaveCount(0);
    await expect(row.getByText("도착 시 28석 예상해요", { exact: true })).toHaveCount(0);
  });
}
