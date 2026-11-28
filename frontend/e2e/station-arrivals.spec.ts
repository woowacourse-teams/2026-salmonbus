import type { StationArrivalsResponse } from "../src/shared/api/stationArrivals.types";
import { test, expect } from "./fixtures/api";
import { observedAt, routeId } from "./fixtures/mock-data";
import { createStationArrivalsData } from "./fixtures/station-arrivals";
import { openBoard, targetRow, targetStop } from "./utils/verdict-board";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

const now = Date.parse(observedAt) + 1_000;
const arrivalsPath = "/api/v2/stations/10011/arrivals";

function readyArrivals(): StationArrivalsResponse {
  const response = createStationArrivalsData(
    new URL(`${arrivalsPath}?routeId=${routeId}&referenceVersionId=1&stopSequence=11`, "https://example.test"),
    now,
  );
  response.status = "READY";
  const route = response.routes[0]!;
  route.status = "READY";
  // watch와 다른 순서·거리로 응답해 watch 목록 유지와 차량 ID별 시간 결합을 확인한다.
  route.arrivals = [
    { vehicleId: "bus-2", ordinal: 1 as const, remainingStops: 6, seconds: 240 },
    { vehicleId: "bus-1", ordinal: 2 as const, remainingStops: 2, seconds: 120 },
  ].map(({ seconds, ...vehicle }) => ({
    ...vehicle,
    eta: {
      status: "AVAILABLE",
      reason: null,
      resolution: "SECONDS",
      estimateBaseAt: new Date(now).toISOString(),
      baseKind: "RECEIVED_AT_APPROXIMATION",
      expectedArrivalAt: new Date(now + seconds * 1_000).toISOString(),
      remainingSeconds: seconds,
    },
    // 현재 잔여석은 도착 시 좌석 예측을 대체하지 않는다.
    currentSeat: { kind: "EXACT", remaining: 28, reason: null },
    congestionCode: null,
    vehicleTypeCode: null,
    taglessSupported: null,
  }));
  return response;
}

test("펼친 목록은 watch 순서·거리·좌석을 유지하고 같은 차량의 ETA 시간만 붙인다", async ({ page, api }) => {
  api.data.arrivals = readyArrivals();
  const stop = api.data.watch.stops.find((stop) => stop.sequence === 11)!;
  stop.approachingVehicles.push({
    vehicleId: "watch-only",
    remainingStops: 10,
    forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 3 },
  });
  await openBoard(page);
  expect(api.requests.arrivals).toBe(0);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /2분 남음\s*3정류장 전\s*도착 시 5석 예상해요/,
    /4분 남음\s*7정류장 전\s*도착 시 2석 예상해요/,
    /10정류장 전\s*도착 시 3석 예상해요/,
  ]);
  expect(api.arrivalRequests).toHaveLength(1);
  expect(api.arrivalRequests[0]!.pathname).toBe(arrivalsPath);
  expect(Object.fromEntries(api.arrivalRequests[0]!.searchParams)).toEqual({
    routeId,
    referenceVersionId: "1",
    stopSequence: "11",
  });
});

test("40초 권장 시각에 재조회하고 닫으면 폴링을 멈춘다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.click();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveCount(2);
  await expect(targetRow(page, "UP").getByText("2분 남음", { exact: true })).toBeVisible();
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(39_000);
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(1_100);
  await expect.poll(() => api.requests.arrivals).toBe(2);
  await stop.click();
  await expect(stop).toHaveAttribute("aria-expanded", "false");
  await page.clock.fastForward(40_000);
  expect(api.requests.arrivals).toBe(2);

  await test.step("펼친 상세 영역을 눌러도 접힌다", async () => {
    await stop.click();
    await expect(stop).toHaveAttribute("aria-expanded", "true");
    await targetRow(page, "UP").getByRole("listitem").first().click();
    await expect(stop).toHaveAttribute("aria-expanded", "false");
  });
});

test("최초 수집 중에도 좌석을 유지하고 권장 시각 뒤 시간만 보탠다", async ({ page, api }) => {
  const collecting = readyArrivals();
  Object.assign(collecting, {
    generatedAt: new Date(now + 880).toISOString(),
    status: "UNAVAILABLE",
    reason: "NOT_COLLECTED",
    snapshot: null,
    routes: [],
  });
  collecting.refresh = {
    status: "RUNNING",
    reason: "COLLECTING",
    suggestedRefreshAt: new Date(now + 880 + 2_000).toISOString(),
  };
  api.data.arrivals = collecting;
  await openBoard(page);
  await page.clock.fastForward(880);
  const arrivalResponse = page.waitForResponse((response) => new URL(response.url()).pathname === arrivalsPath);
  await targetStop(page, "UP").click();
  await (await arrivalResponse).finished();
  await page.clock.runFor(1);
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /3정류장 전\s*도착 시 5석 예상해요/,
    /7정류장 전\s*도착 시 2석 예상해요/,
  ]);
  api.data.arrivals = readyArrivals();
  await page.clock.fastForward(1_900);
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(200);
  await expect(targetRow(page, "UP").getByText("2분 남음", { exact: true })).toBeVisible();
  expect(api.requests.arrivals).toBe(2);
});
