import { apiPaths, pollIntervalMs, targetStopNames } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import { selectRoute } from "./utils/route-select";
import { directionButton, expectDirection, openBoard, targetRow, targetStop } from "./utils/verdict-board";

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

test("자동 갱신 실패 시 기존 예측을 유지하고 다음 요청 성공 시 새 예측으로 교체한다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.scrollIntoViewIfNeeded();
  await stop.click();

  const arrivals = targetRow(page, "UP").getByRole("listitem");
  await expect(arrivals).toHaveText([/3정류장 전\s*도착 시 5석 예상해요/, /7정류장 전\s*도착 시 2석 예상해요/]);
  const previousForecast = await arrivals.allTextContents();
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

  await expect(stop).toHaveAttribute("aria-expanded", "true");
  await expect(arrivals).toHaveText(previousForecast);
  await expect(arrivals.nth(0)).toBeVisible();
  await expect(arrivals.nth(1)).toBeVisible();

  // 이전 화면의 유지와 새 응답의 반영을 구분하도록 예측 값과 항목 수를 바꾼다.
  const stopData = api.data.board.stops.find((stop) => stop.name === targetStopNames.UP);
  if (stopData === undefined) throw new Error("상행 목표 정류장 fixture가 필요합니다");
  stopData.approachingVehicles = [
    { vehicleId: "bus-1", horizonStops: 2, seatAvailableProbability: 0.9, expectedSeats: 9 },
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

test("운행 중인 노선의 특정 정류장에 예측이 없으면 예측 없음 안내를 표시한다", async ({ page, api }) => {
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
