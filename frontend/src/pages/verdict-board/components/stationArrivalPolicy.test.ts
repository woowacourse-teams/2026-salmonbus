import { describe, expect, it } from "@jest/globals";
import type { ArrivalVehicle, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";
import { stationArrivalPanelFor, type StationArrivalDetails } from "./stationArrivalPolicy";
import type { ArrivalView } from "./arrivalPolicy";

const now = Date.parse("2026-10-16T03:00:00Z");
const arrival: ArrivalView = {
  kind: "available",
  vehicleId: "bus-1",
  stopsAway: 3,
  level: "high",
  seatEstimate: { kind: "count", seats: 15 },
};
const unavailable = { available: false, label: "시간 정보 없음" };

function detailsWith(seconds = 120) {
  const vehicle: ArrivalVehicle = {
    vehicleId: "bus-1",
    ordinal: 1,
    remainingStops: 3,
    eta: {
      status: "AVAILABLE",
      reason: null,
      resolution: "SECONDS",
      estimateBaseAt: new Date(now).toISOString(),
      baseKind: "RECEIVED_AT_APPROXIMATION",
      expectedArrivalAt: new Date(now + seconds * 1000).toISOString(),
      remainingSeconds: seconds,
    },
    currentSeat: { kind: "EXACT", remaining: 28, reason: null },
    congestionCode: null,
    vehicleTypeCode: null,
    taglessSupported: null,
  };
  return {
    selection: {
      stationId: "206000637",
      routeId: "204000057",
      referenceVersionId: "7",
      stopSequence: 22,
      direction: "UP",
    },
    resource: {
      status: "success",
      lifetime: { noStore: false, maxAgeSeconds: 5, ageSeconds: 0 },
      latestSuccess: {
        receivedAt: now,
        clock: { servedAt: now, now: () => now },
        body: {
          stationId: "206000637",
          generatedAt: new Date(now).toISOString(),
          status: "READY",
          reason: null,
          snapshot: {
            snapshotId: "snapshot",
            receivedAt: new Date(now).toISOString(),
            sourceObservedAt: null,
            sourceQueryTime: null,
            refreshAfterAt: new Date(now + 40000).toISOString(),
            staleAt: new Date(now + 60000).toISOString(),
          },
          refresh: { status: "IDLE", reason: null, suggestedRefreshAt: new Date(now + 40000).toISOString() },
          routes: [
            {
              routeId: "204000057",
              name: "3330",
              status: "READY",
              reason: null,
              targetVisit: {
                referenceVersionId: "7",
                stopSequence: 22,
                sourceStopOrder: 22,
                matchStatus: "MATCHED",
                direction: "UP",
              },
              arrivals: [vehicle],
            },
          ],
        } as StationArrivalsResponse,
      },
    },
  } satisfies StationArrivalDetails;
}

function rows(details = detailsWith(), forecasts: ArrivalView[] = [arrival]) {
  const panel = stationArrivalPanelFor(details, forecasts);
  if (panel.kind !== "ready") throw new Error(`Expected ready, got ${panel.kind}`);
  return panel.arrivals;
}

describe("stationArrivalPanelFor", () => {
  it("watch 거리와 무관하게 ETA 목록과 순서를 쓰고 같은 차량의 좌석만 연결한다", () => {
    const details = detailsWith();
    const route = details.resource.latestSuccess.body.routes[0]!;
    route.arrivals[0]!.remainingStops = 2;
    route.arrivals.unshift({ ...route.arrivals[0]!, vehicleId: "eta-only", ordinal: 2, remainingStops: 1 });
    const forecasts: ArrivalView[] = [{ ...arrival, vehicleId: "watch-only" }, arrival];
    expect(rows(details, forecasts)).toEqual([
      {
        ordinal: 2,
        remainingStops: 1,
        timing: { available: true, label: "2분 남음" },
        tone: "unavailable",
        seatLabel: "예상 좌석 정보 없음",
      },
      {
        ordinal: 1,
        remainingStops: 2,
        timing: { available: true, label: "2분 남음" },
        tone: "high",
        seatLabel: "도착 시 15석 예상해요",
      },
    ]);
  });

  it.each([0, -1, 59, 60, 61, 120, 121])("예상 시각까지 %i초 남은 경계를 처리한다", (seconds) => {
    const expected =
      seconds <= 0
        ? unavailable
        : { available: true, label: seconds <= 60 ? "1분 이내" : `${Math.ceil(seconds / 60)}분 남음` };
    expect(rows(detailsWith(seconds))[0]!.timing).toEqual(expected);
  });

  it.each(["stationId", "routeId", "referenceVersionId", "stopSequence", "direction"] as const)(
    "선택한 %s가 다르면 목록을 표시하지 않는다",
    (field) => {
      const details = detailsWith();
      const selection = {
        ...details.selection,
        [field]: field === "stopSequence" ? 23 : "different",
      } as StationArrivalDetails["selection"];
      expect(stationArrivalPanelFor({ ...details, selection }, [arrival])).toEqual({ kind: "unavailable" });
    },
  );

  it("검증되지 않은 정류장 방문은 표시하지 않는다", () => {
    const details = detailsWith();
    details.resource.latestSuccess.body.routes[0]!.targetVisit.matchStatus = "UNVERIFIED";
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "unavailable" });
  });

  it.each([null, "missing"])("차량 ID가 %s여도 ETA는 표시하고 좌석만 미제공으로 남긴다", (vehicleId) => {
    const details = detailsWith();
    details.resource.latestSuccess.body.routes[0]!.arrivals[0]!.vehicleId = vehicleId;
    const row = rows(details, [{ ...arrival, vehicleId: null }, arrival])[0]!;
    expect(row.timing).toEqual({ available: true, label: "2분 남음" });
    expect(row.seatLabel).toBe("예상 좌석 정보 없음");
  });

  it("watch 또는 ETA에 차량 ID가 중복되면 좌석을 추측하지 않는다", () => {
    const details = detailsWith();
    expect(rows(details, [arrival, arrival])[0]!.seatLabel).toBe("예상 좌석 정보 없음");
    const vehicles = details.resource.latestSuccess.body.routes[0]!.arrivals;
    vehicles.push({ ...vehicles[0]!, ordinal: 2 });
    expect(rows(details).map(({ seatLabel }) => seatLabel)).toEqual(["예상 좌석 정보 없음", "예상 좌석 정보 없음"]);
  });

  it("watch 예보가 하나도 없어도 ETA 차량을 표시하며 현재 잔여석을 예보로 쓰지 않는다", () => {
    expect(rows(detailsWith(), [])[0]).toMatchObject({
      remainingStops: 3,
      tone: "unavailable",
      seatLabel: "예상 좌석 정보 없음",
    });
  });

  it("ETA 거리 0이면 watch 거리와 무관하게 시간을 비운다", () => {
    const details = detailsWith();
    details.resource.latestSuccess.body.routes[0]!.arrivals[0]!.remainingStops = 0;
    expect(rows(details)[0]).toMatchObject({ remainingStops: 0, timing: null });
    expect(rows(detailsWith(), [{ ...arrival, stopsAway: 0 }])[0]!.timing).toEqual({
      available: true,
      label: "2분 남음",
    });
  });

  it("ETA 거리가 null이면 watch 거리로 채우지 않고 시간은 표시한다", () => {
    const details = detailsWith();
    details.resource.latestSuccess.body.routes[0]!.arrivals[0]!.remainingStops = null;
    expect(rows(details)[0]).toMatchObject({ remainingStops: null, timing: { available: true, label: "2분 남음" } });
  });

  it("유효하지 않은 ETA 시간은 도착으로 바꾸지 않는다", () => {
    for (const eta of [
      { status: "UNAVAILABLE" as const },
      { remainingSeconds: null },
      { expectedArrivalAt: "invalid" },
    ]) {
      const details = detailsWith();
      Object.assign(details.resource.latestSuccess.body.routes[0]!.arrivals[0]!.eta, eta);
      expect(rows(details)[0]).toMatchObject({ remainingStops: 3, timing: unavailable });
    }
  });

  it("스냅샷 만료 즉시 목록을 숨기고 watch 거리로 되돌아가지 않는다", () => {
    const details = detailsWith();
    details.resource.latestSuccess.clock.now = () => now + 60000;
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "stale" });
    details.resource.latestSuccess.clock.now = () => now;
    details.resource.latestSuccess.body.status = "UNAVAILABLE";
    details.resource.latestSuccess.body.reason = "STALE";
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "stale" });
  });

  it("최초 조회와 최초 수집 중에는 확인 중 상태를 반환한다", () => {
    const details = detailsWith();
    expect(stationArrivalPanelFor(null, [arrival])).toEqual({ kind: "loading" });
    expect(stationArrivalPanelFor({ ...details, resource: { status: "pending" } }, [arrival])).toEqual({
      kind: "loading",
    });
    Object.assign(details.resource.latestSuccess.body, {
      status: "UNAVAILABLE",
      reason: "NOT_COLLECTED",
      snapshot: null,
    });
    details.resource.latestSuccess.body.refresh.status = "RUNNING";
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "loading" });
  });

  it("빈 결과는 요청 실패와 구분하고 watch 차량을 대신 표시하지 않는다", () => {
    const details = detailsWith();
    const body = details.resource.latestSuccess.body;
    body.status = "NO_PREDICTION";
    body.routes[0]!.status = "NO_PREDICTION";
    body.routes[0]!.arrivals = [];
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "empty" });
    body.routes = [];
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "empty" });
    expect(
      stationArrivalPanelFor({ ...details, resource: { status: "loadingError", failure: { kind: "timeout" } } }, [
        arrival,
      ]),
    ).toEqual({ kind: "unavailable" });
  });

  it("시계나 snapshot이 없거나 노선이 제공 불가이면 목록을 표시하지 않는다", () => {
    const details = detailsWith();
    expect(
      stationArrivalPanelFor(
        {
          ...details,
          resource: { ...details.resource, latestSuccess: { ...details.resource.latestSuccess, clock: null } },
        },
        [arrival],
      ),
    ).toEqual({ kind: "unavailable" });
    details.resource.latestSuccess.body.routes[0]!.status = "UNAVAILABLE";
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "unavailable" });
    details.resource.latestSuccess.body.snapshot = null;
    expect(stationArrivalPanelFor(details, [arrival])).toEqual({ kind: "unavailable" });
  });

  it("재조회 실패 중에도 만료 전 ETA는 유지한다", () => {
    const details = detailsWith();
    const failed: StationArrivalDetails = {
      ...details,
      resource: {
        status: "refetchError",
        failure: { kind: "network", cause: null },
        latestSuccess: details.resource.latestSuccess,
      },
    };
    expect(stationArrivalPanelFor(failed, [arrival])).toEqual(stationArrivalPanelFor(details, [arrival]));
    details.resource.latestSuccess.clock.now = () => now + 60000;
    expect(stationArrivalPanelFor(failed, [arrival])).toEqual({ kind: "stale" });
  });
});
