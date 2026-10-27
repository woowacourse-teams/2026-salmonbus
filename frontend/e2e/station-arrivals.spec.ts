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
  // 판정보드 표시 순서와 다른 순서로 응답해 ordinal 대신 vehicleId로 결합하는지 확인한다.
  route.arrivals = [
    { vehicleId: "bus-2", ordinal: 1 as const, remainingStops: 7, seconds: 240 },
    { vehicleId: "bus-1", ordinal: 2 as const, remainingStops: 3, seconds: 120 },
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

test("정류장을 펼칠 때 선택한 방문으로 조회하고 차량 ID에 맞춰 거리·시간·좌석을 함께 표시한다", async ({
  page,
  api,
}) => {
  api.data.arrivals = readyArrivals();
  await openBoard(page);
  expect(api.requests.arrivals).toBe(0);

  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /3정류장 전\s*2분 남음\s*도착 시 5석 예상해요/,
    /7정류장 전\s*4분 남음\s*도착 시 2석 예상해요/,
  ]);
  expect(api.arrivalRequests).toHaveLength(1);
  expect(api.arrivalRequests[0]!.pathname).toBe(arrivalsPath);
  expect(Object.fromEntries(api.arrivalRequests[0]!.searchParams)).toEqual({
    routeId,
    referenceVersionId: "1",
    stopSequence: "11",
  });
});

for (const mismatch of ["vehicle", "visit"] as const) {
  test(`${mismatch === "vehicle" ? "차량 ID가 없거나 다른" : "대상 방문이 다른"} 도착정보를 기존 차량에 잘못 붙이지 않는다`, async ({
    page,
    api,
  }) => {
    const response = readyArrivals();
    const route = response.routes[0]!;
    if (mismatch === "vehicle") {
      route.arrivals[0]!.vehicleId = "another-bus";
      route.arrivals[1]!.vehicleId = null;
    } else {
      route.targetVisit.stopSequence = 12;
    }
    api.data.arrivals = response;
    await openBoard(page);
    const arrivalResponse = page.waitForResponse((response) => new URL(response.url()).pathname === arrivalsPath);
    await targetStop(page, "UP").click();
    await (await arrivalResponse).finished();
    await page.clock.runFor(1);

    await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
      /3정류장 전\s*시간 정보 없음\s*도착 시 5석 예상해요/,
      /7정류장 전\s*시간 정보 없음\s*도착 시 2석 예상해요/,
    ]);
    await expect.poll(() => api.requests.arrivals).toBe(1);
  });
}

test("도착정보 거리가 판정 보드 거리와 다르면 판정 보드 거리를 두고 시간 정보 없음으로 표시한다", async ({
  page,
  api,
}) => {
  const response = readyArrivals();
  response.routes[0]!.arrivals[1]!.remainingStops = 2;
  api.data.arrivals = response;
  await openBoard(page);
  await targetStop(page, "UP").click();

  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /3정류장 전\s*시간 정보 없음\s*도착 시 5석 예상해요/,
    /7정류장 전\s*4분 남음\s*도착 시 2석 예상해요/,
  ]);
});

test("HTTP 캐시 5초 대신 응답의 40초 권장 시각에 재조회하고 닫으면 폴링을 멈춘다", async ({ page, api }) => {
  await openBoard(page);
  const stop = targetStop(page, "UP");
  await stop.click();
  await expect(targetRow(page, "UP").getByText("시간 정보 없음", { exact: true })).toHaveCount(2);
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

test("최초 수집 중이면 초 단위 Date와 무관하게 권장하는 2초 뒤 결과를 반영한다", async ({ page, api }) => {
  const collecting = readyArrivals();
  collecting.generatedAt = new Date(now + 880).toISOString();
  collecting.status = "UNAVAILABLE";
  collecting.reason = "NOT_COLLECTED";
  collecting.snapshot = null;
  collecting.routes = [];
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
  await expect(targetRow(page, "UP").getByText("확인 중", { exact: true })).toHaveCount(2);
  expect(api.requests.arrivals).toBe(1);

  api.data.arrivals = readyArrivals();
  await page.clock.fastForward(1_900);
  expect(api.requests.arrivals).toBe(1);
  await page.clock.fastForward(200);
  await expect(targetRow(page, "UP").getByText("2분 남음", { exact: true })).toBeVisible();
  expect(api.requests.arrivals).toBe(2);
});

test("선택 정류장과 방향을 바꾸면 이전 방문의 시간정보를 유지하지 않는다", async ({ page, api }) => {
  api.data.arrivals = readyArrivals();
  await openBoard(page);
  await targetStop(page, "UP").click();
  await expect(targetRow(page, "UP").getByText("2분 남음", { exact: true })).toBeVisible();

  // 같은 방향의 다른 방문을 선택하면 요청의 stationId와 stopSequence도 함께 바뀐다.
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
  await expect(page.getByText("2분 남음", { exact: true })).toHaveCount(0);

  await directionButton(page, "DOWN").click();
  await page.clock.fastForward(40_000);
  expect(api.requests.arrivals).toBe(2);
  await targetStop(page, "DOWN").click();
  await expect.poll(() => api.requests.arrivals).toBe(3);
  expect(api.arrivalRequests[2]!.pathname).toBe("/api/v2/stations/20011/arrivals");
  expect(api.arrivalRequests[2]!.searchParams.get("stopSequence")).toBe("23");
  await expect(targetRow(page, "DOWN").getByRole("listitem")).toHaveText([
    /2정류장 전\s*시간 정보 없음\s*도착 시 8석 예상해요/,
  ]);
});

for (const expires of ["eta", "snapshot"] as const) {
  test(`${expires === "eta" ? "예상 도착시각이 지나면" : "수신 결과가 만료되면"} 남은 시간을 감추고 좌석 예측을 유지한다`, async ({
    page,
    api,
  }) => {
    const response = readyArrivals();
    if (expires === "eta") {
      response.routes[0]!.arrivals[1]!.eta.expectedArrivalAt = new Date(now + 2_000).toISOString();
    } else {
      response.snapshot!.staleAt = new Date(now + 2_000).toISOString();
    }
    api.data.arrivals = response;
    await openBoard(page);
    await targetStop(page, "UP").click();
    const first = targetRow(page, "UP").getByRole("listitem").first();
    await expect(first.getByText(expires === "eta" ? "1분 이내" : "2분 남음", { exact: true })).toBeVisible();

    await page.clock.fastForward(2_000);
    await expect(first.getByText("시간 정보 없음", { exact: true })).toBeVisible();
    await expect(first.getByText("도착 시 5석 예상해요", { exact: true })).toBeVisible();
    await expect(first.getByText(/도착했|도착 완료/)).toHaveCount(0);
  });
}

test("도착정보 조회에 실패해도 기존 거리와 좌석 예측을 표시한다", async ({ page, api }) => {
  api.mode.arrivals = "network-error";
  await openBoard(page);
  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === arrivalsPath,
  );
  await targetStop(page, "UP").click();
  await failedRequest;
  await page.clock.runFor(1);

  await expect(targetRow(page, "UP").getByRole("listitem")).toHaveText([
    /3정류장 전\s*시간 정보 없음\s*도착 시 5석 예상해요/,
    /7정류장 전\s*시간 정보 없음\s*도착 시 2석 예상해요/,
  ]);
  expect(api.requests.arrivals).toBe(1);
  await expect(page.getByText(targetStopNames.UP, { exact: true })).toBeVisible();
});
