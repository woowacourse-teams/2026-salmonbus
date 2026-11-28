import { describe, expect, it } from "@jest/globals";
import { remainingTimeAfter, toRemainingTime } from "./remainingTime";

describe("toRemainingTime", () => {
  it("남은 초가 있으면 그 초를 담는다", () => {
    expect(toRemainingTime(23)).toEqual({ kind: "known", seconds: 23 });
  });

  it.each([null, 0, -1, Number.NaN])("남은 초가 %p이면 시간 없음으로 본다", (seconds) => {
    expect(toRemainingTime(seconds)).toEqual({ kind: "unknown" });
  });
});

describe("remainingTimeAfter", () => {
  it("받은 뒤 흐른 초만큼 남은 초를 줄인다", () => {
    expect(remainingTimeAfter(480, 0)).toEqual({ kind: "known", seconds: 480 });
    expect(remainingTimeAfter(480, 479)).toEqual({ kind: "known", seconds: 1 });
  });

  it.each([480, 481])("남은 초가 480인데 %i초가 흘렀으면 시간 없음으로 본다", (elapsed) => {
    expect(remainingTimeAfter(480, elapsed)).toEqual({ kind: "unknown" });
  });

  it("남은 초를 모르면 흐른 시간과 상관없이 시간 없음이다", () => {
    expect(remainingTimeAfter(null, 0)).toEqual({ kind: "unknown" });
    expect(remainingTimeAfter(null, -30)).toEqual({ kind: "unknown" });
  });
});
