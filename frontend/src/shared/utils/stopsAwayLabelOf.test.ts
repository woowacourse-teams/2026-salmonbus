import { describe, expect, it } from "@jest/globals";
import { stopsAwayLabelOf } from "./stopsAwayLabelOf";

describe("남은 정류장 표시 문구", () => {
  it.each([
    [0, "정류장 도착"],
    [1, "1정류장 전"],
    [12, "12정류장 전"],
  ])("%i정류장 남은 차량을 %s로 표시한다", (remainingStops, expected) => {
    expect(stopsAwayLabelOf(remainingStops)).toBe(expected);
  });
});
