import { describe, expect, it } from "@jest/globals";
import { toSeatEstimate } from "./seatGrade";
import { seatLabel } from "./verdictCopy";

describe("seatLabel", () => {
  it("서버에서 1석 미만 소수로 오는 경우는 내림처리해서 빈자리 없음으로 나타낸다", () => {
    expect(seatLabel(toSeatEstimate(0.9))).toBe("도착 시 빈자리가 없어요");
  });
});
