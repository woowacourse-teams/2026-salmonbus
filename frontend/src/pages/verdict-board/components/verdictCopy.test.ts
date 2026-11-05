import { describe, expect, it } from "@jest/globals";
import { arrivalSeatLabel, chipLabel } from "./verdictCopy";

describe("arrivalSeatLabel", () => {
  it("좌석 예보가 없는 차량은 예상 좌석을 모르는 문구로 표시한다", () => {
    expect(arrivalSeatLabel({ kind: "unavailable", vehicleId: "bus-1", stopsAway: 3 })).toBe("예상 좌석 정보 없음");
  });
});

describe("chipLabel", () => {
  it("예측 불가는 탑승 확률이 낮거나 접근 차량이 없는 상태와 구분한다", () => {
    expect(chipLabel("unavailable")).toBe("좌석 예측 정보가 없어요");
    expect(chipLabel("veryLow")).toBe("탑승 확률 매우 낮음");
    expect(chipLabel("noVehicle")).toBe("지금 오는 차량이 없어요");
  });
});
