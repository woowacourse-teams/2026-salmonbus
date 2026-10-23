import { describe, expect, it } from "@jest/globals";
import type { Direction } from "@/shared/api/routeForecast.types";
import { directionLabelOf } from "./directionLabelOf";

describe("방면 표시 문구", () => {
  it.each<[string | undefined, Direction | null, string]>([
    [" 잠실역 ", "UP", "잠실역 방면"],
    ["잠실역", "DOWN", "잠실역 방면"],
    ["잠실역", null, "잠실역 방면"],
    [undefined, "UP", "상행"],
    ["", "DOWN", "하행"],
    ["   ", "UP", "상행"],
    [undefined, null, "방면 정보 없음"],
    ["   ", null, "방면 정보 없음"],
  ])("종점 %s, 방면 %s를 %s로 표시한다", (terminalStopName, direction, expected) => {
    expect(directionLabelOf(terminalStopName, direction)).toBe(expected);
  });
});
