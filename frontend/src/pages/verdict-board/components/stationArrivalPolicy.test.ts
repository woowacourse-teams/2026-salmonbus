import { describe, expect, it } from "@jest/globals";
import type { ArrivalVehicle, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";
import { arrivalTimingFor, type StationArrivalDetails } from "./stationArrivalPolicy";
import type { ArrivalView } from "./arrivalPolicy";

const now = Date.parse("2026-10-16T03:00:00Z");
const arrival: ArrivalView = {
  kind: "available",
  vehicleId: "bus-1",
  stopsAway: 3,
  level: "high",
  seatEstimate: { kind: "count", seats: 15 },
};

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

describe("arrivalTimingFor", () => {
  it("같은 차량의 시간만 연결하고 watch 거리와 좌석 예보는 유지한다", () => {
    const details = detailsWith();
    const route = details.resource.latestSuccess.body.routes[0]!;
    route.arrivals.unshift({ ...route.arrivals[0]!, vehicleId: "another-bus", ordinal: 2, remainingStops: 9 });
    expect(arrivalTimingFor(arrival, details)).toBe("2분 남음");
    expect(arrival.seatEstimate).toEqual({ kind: "count", seats: 15 });
  });

  it.each([0, -1, 59, 60, 61, 120, 121])("예상 시각까지 %i초 남은 경계를 처리한다", (seconds) => {
    const expected = seconds <= 0 ? null : seconds <= 60 ? "1분 이내" : `${Math.ceil(seconds / 60)}분 남음`;
    expect(arrivalTimingFor(arrival, detailsWith(seconds))).toEqual(expected);
  });

  it.each(["stationId", "routeId", "referenceVersionId", "stopSequence", "direction"] as const)(
    "선택한 %s가 다르면 연결하지 않는다",
    (field) => {
      const details = detailsWith();
      const selection = {
        ...details.selection,
        [field]: field === "stopSequence" ? 23 : "different",
      } as StationArrivalDetails["selection"];
      expect(arrivalTimingFor(arrival, { ...details, selection })).toBeNull();
    },
  );

  it("차량 ID가 없거나 다른 차량이거나 중복되면 순서나 거리로 추측하지 않는다", () => {
    const details = detailsWith();
    expect(arrivalTimingFor({ ...arrival, vehicleId: null }, details)).toBeNull();
    expect(arrivalTimingFor({ ...arrival, vehicleId: "missing" }, details)).toBeNull();
    const vehicles = details.resource.latestSuccess.body.routes[0]!.arrivals;
    vehicles.push({ ...vehicles[0]!, ordinal: 2 });
    expect(arrivalTimingFor(arrival, details)).toBeNull();
  });

  it.each([2, 4, 0, null])(
    "ETA 거리 %s가 watch 거리 3과 달라도 같은 차량의 유효한 시간은 표시한다",
    (remainingStops) => {
      const details = detailsWith();
      details.resource.latestSuccess.body.routes[0]!.arrivals[0]!.remainingStops = remainingStops;
      expect(arrivalTimingFor(arrival, details)).toBe("2분 남음");
      expect(arrival.stopsAway).toBe(3);
    },
  );

  it("정류장에 도착한 차량은 도착정보와 상관없이 시간 칸을 비운다", () => {
    const arrived = { ...arrival, stopsAway: 0 };
    expect(arrivalTimingFor(arrived, null)).toBeNull();
    expect(arrivalTimingFor(arrived, detailsWith())).toBeNull();
  });

  it("서버 시각의 스냅샷 만료 즉시 시간을 사용하지 않는다", () => {
    const details = detailsWith();
    details.resource.latestSuccess.clock.now = () => now + 60000;
    expect(arrivalTimingFor(arrival, details)).toBeNull();
    details.resource.latestSuccess.clock.now = () => now;
    details.resource.latestSuccess.body.reason = "STALE";
    expect(arrivalTimingFor(arrival, details)).toBeNull();
  });

  it("최초 조회와 수집 중에는 시간 문구를 표시하지 않는다", () => {
    const details = detailsWith();
    expect(arrivalTimingFor(arrival, { ...details, resource: { status: "idle" } })).toBeNull();
    expect(arrivalTimingFor(arrival, { ...details, resource: { status: "pending" } })).toBeNull();
    const collecting: StationArrivalDetails = {
      ...details,
      resource: {
        ...details.resource,
        latestSuccess: {
          ...details.resource.latestSuccess,
          body: {
            ...details.resource.latestSuccess.body,
            status: "UNAVAILABLE",
            reason: "NOT_COLLECTED",
            snapshot: null,
            refresh: {
              status: "RUNNING",
              reason: "COLLECTING",
              suggestedRefreshAt: new Date(now + 2000).toISOString(),
            },
            routes: [],
          },
        },
      },
    };
    expect(arrivalTimingFor(arrival, collecting)).toBeNull();
  });

  it("빈 ETA나 제공 불가 응답은 시간만 숨기고 watch 거리와 좌석을 변경하지 않는다", () => {
    const details = detailsWith();
    const body = details.resource.latestSuccess.body;
    for (const status of ["NO_PREDICTION", "UNAVAILABLE"] as const) {
      body.status = status;
      expect(arrivalTimingFor(arrival, details)).toBeNull();
      body.status = "READY";
      body.routes[0]!.status = status;
      expect(arrivalTimingFor(arrival, details)).toBeNull();
    }
    body.routes = [];
    expect(arrivalTimingFor(arrival, details)).toBeNull();
    expect(arrival).toMatchObject({ stopsAway: 3, seatEstimate: { kind: "count", seats: 15 } });
  });

  it("시계나 snapshot이 없거나 방문이 검증되지 않으면 시간을 연결하지 않는다", () => {
    const details = detailsWith();
    const latest = details.resource.latestSuccess;
    expect(
      arrivalTimingFor(arrival, {
        ...details,
        resource: { ...details.resource, latestSuccess: { ...latest, clock: null } },
      }),
    ).toBeNull();
    latest.body.routes[0]!.targetVisit.matchStatus = "UNVERIFIED";
    expect(arrivalTimingFor(arrival, details)).toBeNull();
    latest.body.routes[0]!.targetVisit.matchStatus = "MATCHED";
    latest.body.snapshot = null;
    expect(arrivalTimingFor(arrival, details)).toBeNull();
  });

  it("제공 불가이거나 잘못된 ETA 시각은 도착으로 추정하지 않는다", () => {
    for (const eta of [
      { status: "UNAVAILABLE" as const },
      { remainingSeconds: null },
      { expectedArrivalAt: "invalid" },
    ]) {
      const details = detailsWith();
      Object.assign(details.resource.latestSuccess.body.routes[0]!.arrivals[0]!.eta, eta);
      expect(arrivalTimingFor(arrival, details)).toBeNull();
    }
  });

  it("재조회 실패도 아직 유효한 ETA는 유지한다", () => {
    const details = detailsWith();
    expect(
      arrivalTimingFor(arrival, {
        ...details,
        resource: {
          status: "refetchError",
          failure: { kind: "network", cause: null },
          latestSuccess: details.resource.latestSuccess,
        },
      }),
    ).toBe("2분 남음");
    expect(
      arrivalTimingFor(arrival, { ...details, resource: { status: "loadingError", failure: { kind: "timeout" } } }),
    ).toBeNull();
  });
});
