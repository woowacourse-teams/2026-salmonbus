import { describe, expect, it } from "@jest/globals";
import { remainingTimeLabelOf } from "./remainingTimeLabelOf";

describe("남은 시간 표시 문구", () => {
  it.each([
    [1, "1분 이내"],
    [23, "1분 이내"],
    [60, "1분 이내"],
    [61, "2분 남음"],
    [120, "2분 남음"],
    [121, "3분 남음"],
  ])("%i초 남은 차량을 %s로 표시한다", (seconds, expected) => {
    expect(remainingTimeLabelOf({ kind: "known", seconds })).toBe(expected);
  });

  it("남은 시간을 모르면 시간 정보 없음으로 표시한다", () => {
    expect(remainingTimeLabelOf({ kind: "unknown" })).toBe("시간 정보 없음");
  });
});
