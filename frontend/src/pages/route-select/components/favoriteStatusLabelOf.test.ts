import { describe, expect, it } from "@jest/globals";
import { favoriteStatusLabelOf } from "./favoriteStatusLabelOf";

describe("홈 즐겨찾기 상태 안내 문구", () => {
  it.each([
    ["NO_VEHICLE", "도착 예정 차량이 없어요"],
    ["PREPARING", "도착 예보를 준비하고 있어요"],
    ["UNAVAILABLE", "현재 노선에서 이 정류장을 이용할 수 없어요"],
    ["AMBIGUOUS", "승차할 정류장을 다시 확인해 주세요"],
    ["ERROR", "정류장 정보를 불러오지 못했어요"],
  ] as const)("%s 항목은 '%s'로 안내한다", (status, label) => {
    expect(favoriteStatusLabelOf(status)).toBe(label);
  });
});
