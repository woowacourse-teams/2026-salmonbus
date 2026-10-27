import { describe, expect, it } from "@jest/globals";
import type { ApproachingVehicle, Direction } from "@/shared/api/routeForecast.types";
import { boardWith, stopAt } from "@/testing/fixtures";
import { forecastFixtures } from "@/testing/forecastFixtures";
import { stopViewsFor, type ExpandableStopView } from "../components/displayPolicy";
import { boardViewedPropertiesOf, stopExpandedPropertiesOf } from "./boardEventPolicy";

function approaching(seatAvailableProbability: number): ApproachingVehicle[] {
  return [{ vehicleId: null, remainingStops: 2, forecast: { status: "AVAILABLE", seatAvailableProbability } }];
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

const timing = { routeId: "R1", now: Date.parse("2026-09-01T08:13:10+09:00"), loadMs: 1234.6 };

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

  it("route_id는 서버 응답이 아니라 방문의 노선 ID(URL 값)를 쓴다", () => {
    expect(boardViewedPropertiesOf(board, "UP", { ...timing, routeId: "R9" }).route_id).toBe("R9");
  });
});

function expandableStopAt(direction: Direction, sequence: number): ExpandableStopView {
  const stop = stopViewsFor(board, direction).find((candidate) => candidate.sequence === sequence);
  if (stop === undefined || stop.kind === "passThrough") {
    throw new Error(`펼칠 수 있는 정류장 ${sequence}이 없습니다`);
  }
  return stop;
}

const expandContext = { routeId: "R1", direction: "DOWN", rowPosition: 3, expandIndex: 2 } as const;

describe("stopExpandedPropertiesOf", () => {
  it("예보가 있는 정류장은 가장 가까운 버스의 등급과 거리, 카드에 보인 버스 수를 보낸다", () => {
    expect(stopExpandedPropertiesOf(expandableStopAt("DOWN", 4), expandContext)).toMatchObject({
      stop_kind: "boarding",
      forecast_status: "AVAILABLE",
      level: "low",
      stops_away: 2,
      arrival_count: 1,
    });
  });

  it("가장 가까운 버스의 예보가 없으면 UNAVAILABLE로 보내고 등급은 보내지 않는다", () => {
    const properties = stopExpandedPropertiesOf(expandableStopAt("DOWN", 7), expandContext);

    expect(properties).toMatchObject({
      stop_kind: "boarding",
      forecast_status: "UNAVAILABLE",
      stops_away: 1,
      arrival_count: 1,
    });
    expect(properties).not.toHaveProperty("level");
  });

  it("오는 버스가 없는 정류장은 카드에 보인 버스 수를 0으로 보내고 등급과 거리는 보내지 않는다", () => {
    const properties = stopExpandedPropertiesOf(expandableStopAt("DOWN", 6), expandContext);

    expect(properties).toMatchObject({ stop_kind: "noVehicle", arrival_count: 0 });
    expect(properties).not.toHaveProperty("level");
    expect(properties).not.toHaveProperty("stops_away");
    expect(properties).not.toHaveProperty("forecast_status");
  });

  it("정류장 정보와 목록 위치, 펼친 순서를 그대로 옮긴다", () => {
    expect(stopExpandedPropertiesOf(expandableStopAt("DOWN", 4), expandContext)).toMatchObject({
      route_id: "R1",
      direction: "DOWN",
      stop_id: "S4",
      stop_sequence: 4,
      row_position: 3,
      expand_index: 2,
    });
  });
});
