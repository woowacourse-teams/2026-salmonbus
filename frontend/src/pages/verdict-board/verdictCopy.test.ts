import { describe, expect, it } from "@jest/globals";
import { toSeatEstimate } from "./seatGrade";
import { chipLabel, seatLabel } from "./verdictCopy";

describe("seatLabel", () => {
  it("toSeatEstimate가 1석 미만 소수를 0석으로 내린 결과를 받으면 빈자리 없음 문구를 만든다", () => {
    expect(seatLabel(toSeatEstimate(0.9))).toBe("도착 시 빈자리가 없어요");
  });

  it("toSeatEstimate가 예상 좌석 수 없이 만든 결과를 받으면 빈자리 없음과 구분해서 예측 불가 문구를 만든다", () => {
    expect(seatLabel(toSeatEstimate(undefined))).toBe("좌석을 예측하기 어려워요");
  });

  it("toSeatEstimate가 예상 좌석 0석으로 만든 결과를 받으면 예측 불가와 구분해서 빈자리 없음 문구를 만든다", () => {
    expect(seatLabel(toSeatEstimate(0))).toBe("도착 시 빈자리가 없어요");
  });
});

describe("chipLabel", () => {
  it("예측 불가는 탑승 확률이 낮거나 접근 차량이 없는 상태와 구분한다", () => {
    expect(chipLabel("unknown")).toBe("좌석 예측 정보가 없어요");
    expect(chipLabel("veryLow")).toBe("탑승 확률 매우 낮음");
    expect(chipLabel("noForecast")).toBe("지금 오는 차량이 없어요");
  });
});
