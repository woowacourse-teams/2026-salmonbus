import { describe, expect, it } from "@jest/globals";
import {
  exceedsFavoriteLimit,
  favoriteCountFor,
  favoriteErrorOf,
  favoriteStopLabelOf,
  groupNameError,
  selectedGroups,
} from "./favoritePolicy";
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
  it("작업별 실패 문구를 지정해도 한도 초과와 로그인 만료 안내를 유지한다", () => {
    const fallback = "즐겨찾기를 저장하지 못했어요. 다시 시도해 주세요.";
    expect(favoriteErrorOf({ kind: "network", cause: new Error("offline") }, "saveFavorite")).toBe(fallback);
    expect(
      favoriteErrorOf(
        {
          kind: "contract",
          status: 409,
          retryAfterMs: null,
          error: { code: "FAVORITE_LIMIT_EXCEEDED", message: "한도 초과", requestId: "limit" },
        },
        "saveFavorite",
      ),
    ).toBe("한 그룹에는 즐겨찾기를 2개까지 저장할 수 있어요.");
    expect(
      favoriteErrorOf(
        {
          kind: "contract",
          status: 401,
          retryAfterMs: null,
          error: { code: "UNAUTHENTICATED", message: "인증 만료", requestId: "auth" },
        },
        "saveFavorite",
      ),
    ).toBe("로그인이 만료됐어요. 다시 로그인해 주세요.");
  });
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
  it("그룹 개수는 저장하기 전 체크 상태를 따른다", () => {
    const empty: FavoriteGroup = { id: "g2", name: "통학", favorites: [] };
    const otherStop = { ...target, stopId: "200000012" };
    expect(favoriteCountFor(empty, target, [])).toBe(0);
    expect(favoriteCountFor(empty, target, [empty.id])).toBe(1);
    expect(favoriteCountFor(group, target, [group.id])).toBe(2);
    expect(favoriteCountFor(group, target, [])).toBe(1);
    expect(favoriteCountFor(group, otherStop, [group.id])).toBe(3);
  });
  it("한도 검사는 표시하는 개수가 한도를 넘는 그룹이 있을 때만 막는다", () => {
    const empty: FavoriteGroup = { id: "g2", name: "통학", favorites: [] };
    const otherStop = { ...target, stopId: "200000012" };
    expect(exceedsFavoriteLimit([group, empty], otherStop, [empty.id])).toBe(false);
    expect(exceedsFavoriteLimit([group, empty], otherStop, [group.id, empty.id])).toBe(true);
  });
  it.each([" ", "가".repeat(11), "🚌".repeat(11)])("빈 이름과 10자를 넘는 이름을 거절한다: %s", (name) => {
    expect(groupNameError(name)).not.toBeNull();
  });
  it.each([" 출근 ", "👨‍👩‍👧‍👦".repeat(10), "🇰🇷".repeat(10)])("보이는 문자 기준으로 이름을 검증한다: %s", (name) => {
    expect(groupNameError(name)).toBeNull();
  });
  it("카드와 알림 창이 같은 정류장 문구를 쓰게 한다", () => {
    expect(favoriteStopLabelOf({ stop: { name: "강남역" } }, 0)).toBe("강남역");
    expect(favoriteStopLabelOf({ stop: { name: "" } }, 0)).toBe("정류장 정보 없음");
    expect(favoriteStopLabelOf({ stop: {} }, 4)).toBe("정류장 정보 없음");
    expect(favoriteStopLabelOf(undefined, 0)).toBe("즐겨찾기 1");
    expect(favoriteStopLabelOf(null, 2)).toBe("즐겨찾기 3");
  });
});
