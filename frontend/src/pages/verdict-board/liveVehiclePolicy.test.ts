import { describe, expect, it } from "@jest/globals";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { boardWith, liveVehiclesWith, vehicleAt } from "@/testing/fixtures";

const board = boardWith();

describe("liveVehicleViewsFor", () => {
  it("노선 판본이 보드와 다르면 차량을 하나도 그리지 않는다", () => {
    const revised = liveVehiclesWith([vehicleAt(2)], { referenceVersionId: "v2" });

    expect(liveVehicleViewsFor(board, revised, "UP")).toEqual([]);
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
