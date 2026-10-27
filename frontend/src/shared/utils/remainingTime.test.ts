import { describe, expect, it } from "@jest/globals";
import { toRemainingTime } from "./remainingTime";

describe("toRemainingTime", () => {
  it("남은 초가 있으면 그 초를 담는다", () => {
    expect(toRemainingTime(23)).toEqual({ kind: "known", seconds: 23 });
  });

  it.each([null, 0, -1, Number.NaN])("남은 초가 %p이면 시간 없음으로 본다", (seconds) => {
    expect(toRemainingTime(seconds)).toEqual({ kind: "unknown" });
  });
});
