import { describe, expect, it } from "@jest/globals";
import { exceedsFavoriteLimit, favoriteErrorOf, groupNameError, selectedGroups } from "./favoritePolicy";
import type { FavoriteGroup, FavoriteTarget } from "@/shared/api/favorites.types";

const target: FavoriteTarget = { routeId: "200000001", stopId: "200000011", direction: "UP" };
const group: FavoriteGroup = {
  id: "g1",
  name: "즐겨찾기",
  favorites: [
    { ...target, id: "f1" },
    { ...target, direction: "DOWN", id: "f2" },
  ],
};

describe("즐겨찾기 정책", () => {
  it.each(["INVALID_FAVORITE_GROUP_ORDER", "INVALID_FAVORITE_ORDER"] as const)(
    "%s 오류에 목록 갱신 후 순서 저장을 안내한다",
    (code) => {
      expect(
        favoriteErrorOf({
          kind: "contract",
          error: { code, message: "순서 오류", requestId: "request-1" },
          status: 400,
          retryAfterMs: null,
        }),
      ).toBe("목록이 변경됐어요. 다시 확인한 뒤 순서를 저장해 주세요.");
    },
  );
  it("같은 정류장이어도 방면이 다르면 다른 즐겨찾기로 구분한다", () => {
    expect(selectedGroups([group], { ...target, stopId: "200000012" })).toEqual([]);
    expect(selectedGroups([group], target)).toEqual(["g1"]);
  });
  it("2개가 찬 그룹의 기존 선택 유지와 해제는 허용하고 새 등록만 제한한다", () => {
    expect(exceedsFavoriteLimit([group], target, [group.id])).toBe(false);
    expect(exceedsFavoriteLimit([group], { ...target, stopId: "200000012" }, [group.id])).toBe(true);
    expect(exceedsFavoriteLimit([group], { ...target, stopId: "200000012" }, [])).toBe(false);
    expect(group.favorites).toHaveLength(2);
  });
  it.each([" ", "가".repeat(11), "🚌".repeat(11)])("빈 이름과 10자를 넘는 이름을 거절한다: %s", (name) => {
    expect(groupNameError(name)).not.toBeNull();
  });
  it.each([" 출근 ", "👨‍👩‍👧‍👦".repeat(10), "🇰🇷".repeat(10)])("보이는 문자 기준으로 이름을 검증한다: %s", (name) => {
    expect(groupNameError(name)).toBeNull();
  });
});
