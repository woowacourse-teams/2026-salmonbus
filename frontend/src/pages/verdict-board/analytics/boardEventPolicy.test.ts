import { describe, expect, it } from "@jest/globals";
import type { ApproachingVehicle } from "@/shared/api/routeForecast.types";
import { boardWith, stopAt } from "@/testing/fixtures";
import { forecastFixtures } from "@/testing/forecastFixtures";
import { boardViewedPropertiesOf } from "./boardEventPolicy";

function approaching(seatAvailableProbability: number): ApproachingVehicle[] {
  return [{ vehicleId: null, horizonStops: 2, forecast: { status: "AVAILABLE", seatAvailableProbability } }];
}

const board = boardWith({
  observedAt: "2026-09-01T08:12:40+09:00",
  vehiclesInService: 3,
  stops: [
    stopAt(1, { boardingAllowed: false }),
    stopAt(2, { approachingVehicles: approaching(0.9) }),
    stopAt(3),
    stopAt(4, { approachingVehicles: approaching(0.5) }),
    stopAt(5, { approachingVehicles: approaching(0.1) }),
    stopAt(6),
    stopAt(7, { approachingVehicles: [forecastFixtures.unavailable] }),
  ],
});

const timing = { now: Date.parse("2026-09-01T08:13:10+09:00"), loadMs: 1234.6 };

describe("boardViewedPropertiesOf", () => {
  it("보고 있는 방향의 정류장만 센다", () => {
    expect(boardViewedPropertiesOf(board, "UP", timing)).toMatchObject({
      boarding_stop_count: 2,
      forecast_stop_count: 1,
      high_count: 1,
      low_count: 0,
      very_low_count: 0,
      unavailable_count: 0,
    });
  });

  it("경유 정류장은 탈 수 있는 정류장에서 빼고, 오는 버스가 없는 정류장은 등급에서 제외한다", () => {
    expect(boardViewedPropertiesOf(board, "DOWN", timing)).toMatchObject({
      boarding_stop_count: 4,
      forecast_stop_count: 2,
      high_count: 0,
      low_count: 1,
      very_low_count: 1,
    });
  });

  it("예측 불가 정류장은 unavailable_count로 따로 세고 예보가 있는 정류장 수에서 뺀다", () => {
    expect(boardViewedPropertiesOf(board, "DOWN", timing)).toMatchObject({
      forecast_stop_count: 2,
      unavailable_count: 1,
    });
  });

  it.each(["UP", "DOWN"] as const)("%s 방향의 등급별 개수를 모두 더하면 예보가 있는 정류장 수와 같다", (direction) => {
    const properties = boardViewedPropertiesOf(board, direction, timing);

    expect(properties.high_count + properties.low_count + properties.very_low_count).toBe(
      properties.forecast_stop_count,
    );
  });

  it("예보를 관측한 뒤 지난 시간을 초로 남긴다", () => {
    expect(boardViewedPropertiesOf(board, "UP", timing).forecast_age_sec).toBe(30);
  });

  it("보드 정보와 걸린 시간을 그대로 옮긴다", () => {
    expect(boardViewedPropertiesOf(board, "UP", timing)).toMatchObject({
      route_id: "R1",
      direction: "UP",
      model_release_id: "test",
      vehicles_in_service: 3,
      load_ms: 1235,
    });
  });
});
