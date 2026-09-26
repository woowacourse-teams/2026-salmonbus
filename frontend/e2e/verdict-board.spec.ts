import { apiPaths, pollIntervalMs, targetStopNames } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import { selectRoute } from "./utils/route-select";
import { directionButton, expectDirection, openBoard, targetRow, targetStop } from "./utils/verdict-board";
import { forecastFixtures } from "../src/testing/forecastFixtures";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

test("상행에서 하행으로 전환하면 하행 정류장만 순서대로 표시된다", async ({ page }) => {
  await openBoard(page);
  await directionButton(page, "UP").click();
  await expectDirection(page, "UP");

  await directionButton(page, "DOWN").click();
  await expectDirection(page, "DOWN");
});

test("판정 보드 최초 조회 실패는 운행 없음이나 예측 없음과 구분된다", async ({ page, api }) => {
  api.mode.board = "network-error";
  await page.goto("/");
  await selectRoute(page);

  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).toBeVisible();
  await expect(page.locator("button[aria-expanded]")).toHaveCount(0);
  await expect(page.getByText(/도착 시 .*석 예상해요/)).toHaveCount(0);
  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).not.toBeVisible();
  await expect(page.getByText("지금 오는 차량이 없어요", { exact: true })).not.toBeVisible();
  await expect(page.getByText("현재 예측할 수 있는 차량이 없습니다", { exact: true })).not.toBeVisible();
});

test("유효기간 안의 자동 갱신 실패 시 마지막 예측을 유지한다 (현재 구현)", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();

  const arrivals = targetRow(page, "UP").getByRole("listitem");
  await expect(arrivals).toHaveText([/3정류장 전\s*도착 시 5석 예상해요/, /7정류장 전\s*도착 시 2석 예상해요/]);
  const previousForecast = await arrivals.allTextContents();
  const requestsBeforePoll = api.requests.board;
  api.mode.board = "network-error";

  // 단순히 화면이 남았는지만 보지 않고 후속 폴링의 실제 네트워크 실패를 기다린다.
  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.board,
  );
  await page.clock.fastForward(pollIntervalMs);
  await failedRequest;
  await page.clock.runFor(1);
  expect(api.requests.board).toBeGreaterThan(requestsBeforePoll);
  expect(await page.evaluate(() => Date.now())).toBeLessThan(Date.parse(api.data.board.staleAt));

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
  await expect(arrivals).toHaveText([/3정류장 전\s*도착 시 5석 예상해요/, /7정류장 전\s*도착 시 2석 예상해요/]);
  const requestsBeforeFailure = api.requests.board;
  api.mode.board = "network-error";

  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.board,
  );
  await page.clock.fastForward(pollIntervalMs);
  await failedRequest;
  await page.clock.runFor(1);
  expect(api.requests.board).toBeGreaterThan(requestsBeforeFailure);
  expect(await page.evaluate(() => Date.now())).toBeLessThan(Date.parse(api.data.board.staleAt));

  // 실패 직후 기존 예측의 유지 여부는 별도 테스트에서 검증한다.
  // 다음 성공 응답이 반영됐는지 구분하도록 예측 값과 항목 수를 바꾼다.
  const stopData = api.data.board.stops.find((stop) => stop.name === targetStopNames.UP);
  if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
  stopData.approachingVehicles = [
    {
      vehicleId: "bus-1",
      horizonStops: 2,
      forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9, expectedSeats: 9 },
    },
  ];
  api.mode.board = "success";
  const requestsBeforeRecovery = api.requests.board;

  const recoveredResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.board && response.status() === 200,
  );
  await page.clock.fastForward(pollIntervalMs);
  await (await recoveredResponse).finished();
  expect(api.requests.board).toBeGreaterThan(requestsBeforeRecovery);
  expect(await page.evaluate(() => Date.now())).toBeLessThan(Date.parse(api.data.board.staleAt));

  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(arrivals).toHaveText([/2정류장 전\s*도착 시 9석 예상해요/]);
  await expect(arrivals.nth(0)).toBeVisible();
});

test("운행 중인 차량이 없으면 운행 없음 안내를 표시한다", async ({ page, api }) => {
  api.data.board.vehiclesInService = 0;
  api.data.vehicles.observation.state = "NO_VEHICLES_OBSERVED";
  api.data.vehicles.vehicles = [];
  await openBoard(page);

  await expect(page.getByText("현재는 운행시간이 아닙니다", { exact: true })).toBeVisible();
  await expect(page.getByText("정보를 불러오지 못했어요", { exact: true })).not.toBeVisible();
  await expect(page.locator("button[aria-expanded]")).toHaveCount(0);
  await expect(page.getByText(/도착 시 .*석 예상해요/)).toHaveCount(0);
});

test("운행 중인 노선의 특정 정류장에 접근 차량이 없으면 차량 없음 안내를 표시한다", async ({ page, api }) => {
  const stopData = api.data.board.stops.find((stop) => stop.name === targetStopNames.UP);
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
    seatLabel: "좌석을 예측하기 어려워요",
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
    seatLabel: "좌석을 예측하기 어려워요",
  },
]) {
  test(name, async ({ page, api }) => {
    const stopData = api.data.board.stops.find((stop) => stop.name === targetStopNames.UP);
    if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
    stopData.approachingVehicles = [{ ...vehicle, vehicleId: "bus-1", horizonStops: 3 }];

    // 같은 차량의 현재 좌석이 도착 예측을 대신하지 않는지 확인한다.
    const liveVehicle = api.data.vehicles.vehicles.find((vehicle) => vehicle.vehicleId === "bus-1");
    if (liveVehicle === undefined) throw new Error("bus-1 실시간 차량 fixture가 필요합니다");
    liveVehicle.seat = { kind: "EXACT", remaining: 28 };

    await openBoard(page);
    const stop = targetStop(page, "UP");
    const row = targetRow(page, "UP");
    await stop.scrollIntoViewIfNeeded();
    await expect(row.getByText(chip, { exact: true })).toBeVisible();
    await stop.click();

    await expect(row.getByRole("listitem")).toHaveText([`3정류장 전${seatLabel}`]);
    await expect(row.getByText("지금 오는 차량이 없어요", { exact: true })).toHaveCount(0);
    await expect(row.getByText("도착 시 28석 예상해요", { exact: true })).toHaveCount(0);
  });
}
