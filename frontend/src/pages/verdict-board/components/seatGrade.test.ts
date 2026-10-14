import { describe, expect, it } from "@jest/globals";
import { toSeatEstimate, toSeatLevel } from "./seatGrade";

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

  it("탑승 확률 0도 유효한 예측이므로 매우 낮음이다", () => {
    expect(toSeatLevel(0)).toBe("veryLow");
  });
});

describe("toSeatEstimate", () => {
  it("예상 좌석의 소수점은 내림한다", () => {
    expect(toSeatEstimate(5.9)).toEqual({ kind: "count", seats: 5 });
  });

  it("예상 좌석이 생략되면 모름으로 표시한다", () => {
    expect(toSeatEstimate(undefined)).toEqual({ kind: "unknown" });
  });

  it("예상 좌석 0은 모름과 구분한다", () => {
    expect(toSeatEstimate(0)).toEqual({ kind: "count", seats: 0 });
  });
});
