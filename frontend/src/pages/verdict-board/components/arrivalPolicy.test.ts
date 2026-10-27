import { describe, expect, it } from "@jest/globals";
import type { ApproachingVehicle } from "@/shared/api/routeForecast.types";
import { forecastFixtures } from "@/testing/forecastFixtures";
import { arrivalViewsFor, representativeArrival, type ArrivalView } from "./arrivalPolicy";

describe("arrivalViewsFor", () => {
  it("서버가 보낸 순서를 다시 정렬하지 않고 도착 시간과 연결할 차량 ID를 유지한다", () => {
    const arrivals = arrivalViewsFor([
      { ...forecastFixtures.available, vehicleId: "arrived-bus", remainingStops: 0 },
      { ...forecastFixtures.unavailable, vehicleId: "far-bus", remainingStops: 7 },
      { ...forecastFixtures.available, vehicleId: "near-bus", remainingStops: 2 },
    ]);

    expect(arrivals.map(({ vehicleId, stopsAway }) => ({ vehicleId, stopsAway }))).toEqual([
      { vehicleId: "arrived-bus", stopsAway: 0 },
      { vehicleId: "far-bus", stopsAway: 7 },
      { vehicleId: "near-bus", stopsAway: 2 },
    ]);
  });

  it("forecast 안의 확률과 예상 좌석으로 도착 예측을 만든다", () => {
    expect(arrivalViewsFor([forecastFixtures.available])).toEqual([
      { kind: "available", vehicleId: null, stopsAway: 1, level: "high", seatEstimate: { kind: "count", seats: 5 } },
    ]);
  });

  it("예측이 없어도 차량과 정류장 거리는 남기고 확률을 모름으로 표시한다", () => {
    expect(arrivalViewsFor([{ ...forecastFixtures.unavailable, remainingStops: 7 }])).toEqual([
      { kind: "unavailable", vehicleId: null, stopsAway: 7 },
    ]);
  });

  it("알 수 없는 예측 상태는 모름으로 표시한다", () => {
    const vehicle = {
      ...forecastFixtures.unavailable,
      remainingStops: 7,
      forecast: { status: "UNKNOWN_STATUS" },
    } as unknown as ApproachingVehicle;

    expect(arrivalViewsFor([vehicle])).toEqual([{ kind: "unavailable", vehicleId: null, stopsAway: 7 }]);
  });

  it("예상 좌석만 생략되면 탑승 확률 등급은 유지한다", () => {
    expect(arrivalViewsFor([forecastFixtures.withoutExpectedSeats])).toEqual([
      { kind: "available", vehicleId: null, stopsAway: 1, level: "high", seatEstimate: { kind: "unknown" } },
    ]);
  });

  it("확률과 좌석이 0이면 예측 불가와 구분한다", () => {
    expect(arrivalViewsFor([forecastFixtures.zeroSeats])).toEqual([
      { kind: "available", vehicleId: null, stopsAway: 1, level: "veryLow", seatEstimate: { kind: "count", seats: 0 } },
    ]);
  });

  it("예측 불가 차량도 앞의 세 대에 포함하고 서버가 맨 앞에 보낸 차량을 대표로 선택한다", () => {
    const vehicles = [
      { ...forecastFixtures.unavailable, remainingStops: 1 },
      { ...forecastFixtures.available, remainingStops: 3 },
      { ...forecastFixtures.available, remainingStops: 7 },
      { ...forecastFixtures.available, remainingStops: 9 },
    ];
    const [first, ...rest] = arrivalViewsFor(vehicles);
    if (first === undefined) throw new Error("도착 예측이 비어 있습니다");
    const arrivals: [ArrivalView, ...ArrivalView[]] = [first, ...rest];

    expect(arrivals.map((arrival) => arrival.stopsAway)).toEqual([1, 3, 7]);
    expect(representativeArrival(arrivals)).toEqual({
      kind: "unavailable",
      vehicleId: null,
      stopsAway: 1,
    });
  });
});
