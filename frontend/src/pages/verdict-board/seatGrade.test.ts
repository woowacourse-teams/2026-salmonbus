import { describe, expect, it } from "@jest/globals";
import { toSeatLevel } from "./seatGrade";

describe("toSeatLevel", () => {
  it("탑승 확률이 0.7이면 높음이다", () => {
    expect(toSeatLevel(0.7)).toBe("high");
  });

  it("탑승 확률이 0.7에 못 미치면 낮음이다", () => {
    expect(toSeatLevel(0.69)).toBe("low");
  });

  it("탑승 확률이 0.3이면 낮음이다", () => {
    expect(toSeatLevel(0.3)).toBe("low");
  });

  it("탑승 확률이 0.3에 못 미치면 매우 낮음이다", () => {
    expect(toSeatLevel(0.29)).toBe("veryLow");
  });
});
