import { describe, expect, it } from "@jest/globals";
import { toSeatEstimate } from "./seatGrade";
import { seatLabelOf } from "./seatLabelOf";

describe("예상 좌석 표시 문구", () => {
  it("예상 좌석이 1석 이상이면 좌석 수를 보여 준다", () => {
    expect(seatLabelOf(toSeatEstimate(5))).toBe("도착 시 5석 예상해요");
  });

  it("toSeatEstimate가 1석 미만 소수를 0석으로 내린 결과를 받으면 빈자리 없음 문구를 만든다", () => {
    expect(seatLabelOf(toSeatEstimate(0.9))).toBe("도착 시 빈자리가 없어요");
  });

  it("toSeatEstimate가 예상 좌석 수 없이 만든 결과를 받으면 빈자리 없음과 구분해서 예측 불가 문구를 만든다", () => {
    expect(seatLabelOf(toSeatEstimate(undefined))).toBe("예상 좌석 정보 없음");
  });

  it("toSeatEstimate가 예상 좌석 0석으로 만든 결과를 받으면 예측 불가와 구분해서 빈자리 없음 문구를 만든다", () => {
    expect(seatLabelOf(toSeatEstimate(0))).toBe("도착 시 빈자리가 없어요");
  });
});
