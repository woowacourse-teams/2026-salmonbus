import { describe, expect, it } from "@jest/globals";
import type { Vehicle } from "@/shared/api/routeForecast.types";
import { serviceStateFor, stopViewsFor } from "./displayPolicy";
import { boardWith, stopAt, upInfo, vehicleAt } from "@/testing/fixtures";
import { forecastFixtures } from "@/testing/forecastFixtures";

const observedAt = (clock: string, offset = "+09:00") => `2026-09-01T${clock}:00${offset}`;
const upVehicle = vehicleAt(2);
const downVehicle = vehicleAt(5);
const noVehicles: Vehicle[] = [];

describe("serviceStateFor", () => {
  describe("관측 시각이 시간표 안이면", () => {
    it("관측 시각이 첫차 시각과 같으면 이미 운행중이다", () => {
      expect(serviceStateFor(boardWith({ observedAt: observedAt("04:50") }), upInfo, noVehicles)).toBe("running");
    });

    it("관측 시각이 막차 시각과 같으면 아직 운행중이다", () => {
      expect(serviceStateFor(boardWith({ observedAt: observedAt("23:30") }), upInfo, noVehicles)).toBe("running");
    });

    it("자정을 넘기는 시간표는 새벽을 운행 중으로 본다", () => {
      const overnight = { ...upInfo, firstDepartureTime: "23:00", lastDepartureTime: "02:00" };

      expect(serviceStateFor(boardWith({ observedAt: observedAt("01:00") }), overnight, noVehicles)).toBe("running");
    });

    it("도는 차량이 없으면 운행 종료이다", () => {
      expect(serviceStateFor(boardWith({ vehiclesInService: 0 }), upInfo, noVehicles)).toBe("outOfService");
    });
  });

  describe("관측 시각이 시간표 밖이면", () => {
    const afterLastDeparture = boardWith({ observedAt: observedAt("23:31") });

    it("막차가 떠난 뒤에도 이 방향 차량이 관측되면 운행 중이다", () => {
      expect(serviceStateFor(afterLastDeparture, upInfo, [upVehicle])).toBe("running");
    });

    it("자정이 지나도 이 방향 차량이 관측되면 운행 중이다", () => {
      const afterMidnight = boardWith({ observedAt: observedAt("00:10") });

      expect(serviceStateFor(afterMidnight, upInfo, [upVehicle])).toBe("running");
    });

    it("첫차 시각 전이라도 이 방향 차량이 관측되면 운행 중이다", () => {
      const beforeFirstDeparture = boardWith({ observedAt: observedAt("04:49") });

      expect(serviceStateFor(beforeFirstDeparture, upInfo, [upVehicle])).toBe("running");
    });

    it("노선에 도는 차량이 없으면 운행 종료이다", () => {
      const emptyRoute = boardWith({ observedAt: observedAt("23:31"), vehiclesInService: 0 });

      expect(serviceStateFor(emptyRoute, upInfo, noVehicles)).toBe("outOfService");
    });

    it("반대 방향 차량만 관측되면 운행 종료이다", () => {
      expect(serviceStateFor(afterLastDeparture, upInfo, [downVehicle])).toBe("outOfService");
    });

    it("도는 차량이 있어도 위치를 못 읽어 이 방향 차량이 하나도 없으면 운행 종료이다", () => {
      const unlocatedOnly = boardWith({ observedAt: observedAt("23:31"), vehiclesInService: 2 });

      expect(serviceStateFor(unlocatedOnly, upInfo, noVehicles)).toBe("outOfService");
    });

    it("자정을 넘기는 시간표에서 막차와 첫차 사이 시각도 같은 기준으로 판단한다", () => {
      const overnight = { ...upInfo, firstDepartureTime: "23:00", lastDepartureTime: "02:00" };
      const midday = boardWith({ observedAt: observedAt("12:00") });

      expect(serviceStateFor(midday, overnight, noVehicles)).toBe("outOfService");
    });
  });

  describe("관측 시각이 KST가 아니면", () => {
    it("시간표 판정을 포기하고 차량 수로만 정한다", () => {
      const utcObserved = observedAt("23:31", "+00:00");

      expect(serviceStateFor(boardWith({ observedAt: utcObserved, vehiclesInService: 2 }), upInfo, noVehicles)).toBe(
        "running",
      );
    });
  });
});

describe("stopViewsFor", () => {
  it("예상 좌석 수를 몰라도 확률이 있으면 그 등급으로 표시한다", () => {
    const board = boardWith({
      stops: [stopAt(2, { approachingVehicles: [forecastFixtures.withoutExpectedSeats] })],
    });

    expect(stopViewsFor(board, "UP")).toMatchObject([
      {
        kind: "boarding",
        tone: "high",
        arrivals: [{ kind: "available", level: "high", seatEstimate: { kind: "unknown" } }],
      },
    ]);
  });

  it("가장 가까운 차량의 예측이 없어도 접근 차량과 대표 판정에 남긴다", () => {
    const board = boardWith({
      stops: [
        stopAt(2, {
          approachingVehicles: [{ ...forecastFixtures.available, horizonStops: 7 }, forecastFixtures.unavailable],
        }),
        stopAt(3),
      ],
    });

    expect(stopViewsFor(board, "UP")).toMatchObject([
      {
        sequence: 2,
        kind: "boarding",
        tone: "unavailable",
        arrivals: [
          { kind: "unavailable", stopsAway: 1 },
          { kind: "available", stopsAway: 7, level: "high", seatEstimate: { kind: "count", seats: 5 } },
        ],
      },
      { sequence: 3, kind: "noVehicle" },
    ]);
  });
});
