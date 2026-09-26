import { describe, expect, it } from "@jest/globals";
import { forecastFixtures } from "@/testing/forecastFixtures";
import { arrivalViewsFor, representativeArrival } from "./arrivalPolicy";

describe("arrivalViewsFor", () => {
  it("forecast 안의 확률과 예상 좌석으로 도착 예측을 만든다", () => {
    expect(arrivalViewsFor([forecastFixtures.available])).toEqual([
      { stopsAway: 1, level: "high", seatEstimate: { kind: "count", seats: 5 } },
    ]);
  });

  it("예측이 없어도 차량과 정류장 거리는 남기고 확률을 모름으로 표시한다", () => {
    expect(arrivalViewsFor([{ ...forecastFixtures.unavailable, horizonStops: 7 }])).toEqual([
      { stopsAway: 7, level: "unknown", seatEstimate: { kind: "unknown" } },
    ]);
  });

  it("예상 좌석만 생략되면 탑승 확률 등급은 유지한다", () => {
    expect(arrivalViewsFor([forecastFixtures.withoutExpectedSeats])).toEqual([
      { stopsAway: 1, level: "high", seatEstimate: { kind: "unknown" } },
    ]);
  });

  it("확률과 좌석이 0이면 예측 불가와 구분한다", () => {
    expect(arrivalViewsFor([forecastFixtures.zeroSeats])).toEqual([
      { stopsAway: 1, level: "veryLow", seatEstimate: { kind: "count", seats: 0 } },
    ]);
  });

  it("예측 불가 차량도 거리순 최대 세 대에 포함하고 가장 가까우면 대표로 선택한다", () => {
    const vehicles = [
      { ...forecastFixtures.available, horizonStops: 9 },
      { ...forecastFixtures.available, horizonStops: 7 },
      { ...forecastFixtures.unavailable, horizonStops: 1 },
      { ...forecastFixtures.available, horizonStops: 3 },
    ];
    const arrivals = arrivalViewsFor(vehicles);

    expect(arrivals.map((arrival) => arrival.stopsAway)).toEqual([1, 3, 7]);
    expect(representativeArrival(arrivals)).toEqual({
      stopsAway: 1,
      level: "unknown",
      seatEstimate: { kind: "unknown" },
    });
    expect(vehicles.map((vehicle) => vehicle.horizonStops)).toEqual([9, 7, 1, 3]);
  });
});
