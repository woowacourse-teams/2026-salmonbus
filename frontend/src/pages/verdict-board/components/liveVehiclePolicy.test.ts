import { describe, expect, it } from "@jest/globals";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { boardWith, liveVehiclesWith, stopAt, vehicleAt } from "@/testing/fixtures";

const board = boardWith();

describe("liveVehicleViewsFor", () => {
  it("phase로 같은 이동 구간을 묶고 방향의 끝점은 실제 첫 구간과 마지막 구간에 둔다", () => {
    const sparseBoard = boardWith({
      stops: [10, 30, 60, 100].map((sequence) => stopAt(sequence, { direction: "UP" })),
    });
    const vehicles = [
      vehicleAt(10, { direction: "UP", phase: "ARRIVING" }),
      vehicleAt(10, { direction: "UP", phase: "DEPARTED" }),
      vehicleAt(10, { direction: "UP", phase: "IN_TRANSIT" }),
      vehicleAt(30, { direction: "UP", phase: "ARRIVING" }),
      vehicleAt(100, { direction: "UP", phase: "ARRIVING" }),
      vehicleAt(100, { direction: "UP", phase: "DEPARTED" }),
    ];

    const views = liveVehicleViewsFor(sparseBoard, liveVehiclesWith(vehicles), "UP");

    expect(views.map((view) => view.motionSegment.segmentIndex)).toEqual([0, 0, 0, 0, 2, 2]);
  });

  it("노선 판본이 보드와 다르면 차량을 하나도 그리지 않는다", () => {
    const revised = liveVehiclesWith([vehicleAt(2)], { referenceVersionId: "v2" });

    expect(liveVehicleViewsFor(board, revised, "UP")).toEqual([]);
  });

  it("판본이 같아도 노선이 보드와 다르면 차량을 하나도 그리지 않는다", () => {
    const otherRoute = liveVehiclesWith([vehicleAt(2)], { routeId: "R2" });

    expect(liveVehicleViewsFor(board, otherRoute, "UP")).toEqual([]);
  });

  it("ID가 있는 차량은 phase가 바뀌어도 같은 key를 유지한다", () => {
    const moving = liveVehicleViewsFor(board, liveVehiclesWith([vehicleAt(2, { phase: "IN_TRANSIT" })]), "UP");
    const arriving = liveVehicleViewsFor(board, liveVehiclesWith([vehicleAt(2, { phase: "ARRIVING" })]), "UP");

    expect(moving).toHaveLength(1);
    expect(arriving[0]?.key).toBe(moving[0]?.key);
  });

  it("ID가 없는 차량 두 대가 같은 정류장에 같은 phase로 있어도 둘 다 그리고 key는 서로 다르다", () => {
    const anonymous = liveVehiclesWith([vehicleAt(2, { vehicleId: null }), vehicleAt(2, { vehicleId: null })]);

    const views = liveVehicleViewsFor(board, anonymous, "UP");

    expect(views).toHaveLength(2);
    expect(views[0]?.key).not.toBe(views[1]?.key);
  });
});
