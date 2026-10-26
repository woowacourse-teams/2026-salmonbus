import type { StationArrivalsResponse } from "../src/shared/api/stationArrivals.types";
import { test, expect } from "./fixtures/api";
import { observedAt, routeId, targetStopNames } from "./fixtures/mock-data";
import { createStationArrivalsData } from "./fixtures/station-arrivals";
import { directionButton, openBoard, targetRow, targetStop } from "./utils/verdict-board";

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
  // watch와 다른 순서·거리로 응답해 ETA 순서 유지와 차량 ID별 좌석 결합을 확인한다.
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

test("펼친 목록은 ETA 순서·거리를 쓰고 같은 차량의 watch 좌석만 붙인다", async ({ page, api }) => {
  api.data.arrivals = readyArrivals();
  await openBoard(page);
  expect(api.requests.arrivals).toBe(0);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /6정류장 전\s*4분 남음\s*도착 시 2석 예상해요/,
    /2정류장 전\s*2분 남음\s*도착 시 5석 예상해요/,
  ]);
  expect(api.arrivalRequests).toHaveLength(1);
  expect(api.arrivalRequests[0]!.pathname).toBe(arrivalsPath);
  expect(Object.fromEntries(api.arrivalRequests[0]!.searchParams)).toEqual({
    routeId,
    referenceVersionId: "1",
    stopSequence: "11",
  });
});

test("ETA에만 있는 차량과 ID가 없는 차량은 시간과 거리를 표시하고 좌석은 미제공으로 둔다", async ({ page, api }) => {
  const response = readyArrivals();
  response.routes[0]!.arrivals[0]!.vehicleId = "eta-only";
  response.routes[0]!.arrivals[1]!.vehicleId = null;
  api.data.arrivals = response;
  await openBoard(page);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /6정류장 전\s*4분 남음\s*예상 좌석 정보 없음/,
    /2정류장 전\s*2분 남음\s*예상 좌석 정보 없음/,
  ]);
});

test("선택한 방문과 다른 ETA는 표시하지 않는다", async ({ page, api }) => {
  api.data.arrivals = readyArrivals();
  api.data.arrivals.routes[0]!.targetVisit.stopSequence = 12;
  await openBoard(page);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByText("도착정보를 불러오지 못했어요", { exact: true })).toBeVisible();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveCount(0);
});

test("40초 권장 시각에 재조회하고 닫으면 폴링을 멈춘다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.click();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveCount(2);
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(39_000);
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(1_100);
  await expect.poll(() => api.requests.arrivals).toBe(2);
  await stop.click();
  await expect(stop).toHaveAttribute("aria-expanded", "false");
  await page.clock.fastForward(40_000);
  expect(api.requests.arrivals).toBe(2);
});

test("최초 수집 중에는 목록 대신 안내하고 권장 시각 뒤 결과를 반영한다", async ({ page, api }) => {
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
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByText("도착정보 확인 중이에요", { exact: true })).toBeVisible();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveCount(0);
  api.data.arrivals = readyArrivals();
  await page.clock.fastForward(1_900);
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(200);
  await expect(targetRow(page, "UP").getByText("2분 남음", { exact: true })).toBeVisible();
  expect(api.requests.arrivals).toBe(2);
});

test("선택 정류장과 방향을 바꾸면 이전 방문의 목록을 유지하지 않는다", async ({ page, api }) => {
  api.data.arrivals = readyArrivals();
  await openBoard(page);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByText("4분 남음", { exact: true })).toBeVisible();
  api.data.arrivals = null;
  const nextStop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.sequence === 12)!;
  await page
    .getByRole("region", { name: "판정보드 정류장 목록", exact: true })
    .getByRole("button", { name: new RegExp(`^${nextStop.name}`) })
    .and(page.locator("button[aria-expanded]"))
    .click();
  await expect.poll(() => api.requests.arrivals).toBe(2);
  expect(api.arrivalRequests[1]!.pathname).toBe(`/api/v2/stations/${nextStop.stopId}/arrivals`);
  expect(api.arrivalRequests[1]!.searchParams.get("stopSequence")).toBe("12");
  await expect(targetStop(page, "UP")).toHaveAttribute("aria-expanded", "false");
  await expect(page.getByText("4분 남음", { exact: true })).toHaveCount(0);
  await directionButton(page, "DOWN").click();
  await page.clock.fastForward(40_000);
  expect(api.requests.arrivals).toBe(2);
  await targetStop(page, "DOWN").click();
  await expect.poll(() => api.requests.arrivals).toBe(3);
  expect(api.arrivalRequests[2]!.pathname).toBe("/api/v2/stations/20011/arrivals");
  expect(api.arrivalRequests[2]!.searchParams.get("stopSequence")).toBe("23");
  await expect(targetRow(page, "DOWN").getByRole("listitem")).toHaveText([
    /2정류장 전\s*2분 남음\s*도착 시 8석 예상해요/,
  ]);
});

test("ETA 시간이 지나도 도착으로 추정하지 않고 snapshot 만료 시 목록을 숨긴다", async ({ page, api }) => {
  const response = readyArrivals();
  response.routes[0]!.arrivals[1]!.eta.expectedArrivalAt = new Date(now + 2_000).toISOString();
  response.snapshot!.staleAt = new Date(now + 4_000).toISOString();
  api.data.arrivals = response;
  await openBoard(page);
  await targetStop(page, "UP").click();
  const row = targetRow(page, "UP");
  const vehicle = row.getByRole("listitem").nth(1);
  await expect(vehicle.getByText("1분 이내", { exact: true })).toBeVisible();
  await page.clock.fastForward(2_000);
  await expect(vehicle).toHaveText(/2정류장 전\s*시간 정보 없음\s*도착 시 5석 예상해요/);
  await expect(row.getByText("정류장 도착", { exact: true })).toHaveCount(0);
  await page.clock.fastForward(2_000);
  await expect(row.getByText("도착정보가 만료됐어요. 갱신을 기다리고 있어요", { exact: true })).toBeVisible();
  await expect(row.getByRole("listitem")).toHaveCount(0);
});

test("도착정보 조회 실패 시 watch 목록을 대신 표시하지 않는다", async ({ page, api }) => {
  api.mode.arrivals = "network-error";
  await openBoard(page);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByText("도착정보를 불러오지 못했어요", { exact: true })).toBeVisible();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveCount(0);
  expect(api.requests.arrivals).toBe(1);
  await expect(page.getByText(targetStopNames.UP, { exact: true })).toBeVisible();
});
