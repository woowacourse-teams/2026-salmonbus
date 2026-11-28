import { describe, expect, it } from "@jest/globals";
import type { FavoriteGroup, SavedFavorite } from "@/shared/api/favorites.types";
import { moveItem } from "@/shared/utils/moveItem";
import {
  doneLabelOf,
  groupsWithNewGroup,
  groupsWithOrderChange,
  groupsWithoutFavorite,
  groupsWithoutGroup,
  orderChangesFrom,
} from "./favoriteEditorDraft";

function favorite(id: string): SavedFavorite {
  return { id, routeId: "200000001", stopId: `stop-${id}`, direction: "UP" };
}

const commute: FavoriteGroup = { id: "commute", name: "출퇴근", favorites: [favorite("a"), favorite("b")] };
const school: FavoriteGroup = { id: "school", name: "통학", favorites: [favorite("c"), favorite("d")] };
const saved = [commute, school];

function withReversedFavorites(group: FavoriteGroup): FavoriteGroup {
  return { ...group, favorites: moveItem(group.favorites, 0, 1) };
}

describe("orderChangesFrom", () => {
  it("바꾼 것이 없으면 저장할 변경이 없다", () => {
    expect(orderChangesFrom(saved, [...saved])).toEqual([]);
  });

  it("그룹 순서를 바꾸면 전체 그룹 ID 목록을 저장한다", () => {
    expect(orderChangesFrom(saved, [school, commute])).toEqual([{ groupId: null, ids: ["school", "commute"] }]);
  });

  it("그룹 안 순서를 바꾸면 그 그룹의 전체 즐겨찾기 ID 목록만 저장한다", () => {
    expect(orderChangesFrom(saved, [commute, withReversedFavorites(school)])).toEqual([
      { groupId: "school", ids: ["d", "c"] },
    ]);
  });

  it("둘 다 바꾸면 그룹 순서를 먼저 저장한다", () => {
    expect(orderChangesFrom(saved, [withReversedFavorites(school), commute])).toEqual([
      { groupId: null, ids: ["school", "commute"] },
      { groupId: "school", ids: ["d", "c"] },
    ]);
  });
});

describe("편집 중 그룹을 더하거나 지운 뒤의 저장할 변경", () => {
  const weekend: FavoriteGroup = { id: "weekend", name: "주말", favorites: [] };
  const draft = [withReversedFavorites(school), commute];

  it("그룹을 더해도 저장 전 그룹 순서와 정류장 순서를 그대로 저장한다", () => {
    const savedWithNew = groupsWithNewGroup(saved, weekend);
    const draftWithNew = groupsWithNewGroup(draft, weekend);

    expect(draftWithNew.map((group) => group.id)).toEqual(["school", "commute", "weekend"]);
    expect(orderChangesFrom(savedWithNew, draftWithNew)).toEqual([
      { groupId: null, ids: ["school", "commute", "weekend"] },
      { groupId: "school", ids: ["d", "c"] },
    ]);
  });

  it("그룹을 지워도 남은 그룹의 저장 전 정류장 순서를 그대로 저장한다", () => {
    const savedWithoutCommute = groupsWithoutGroup(saved, "commute");
    const draftWithoutCommute = groupsWithoutGroup(draft, "commute");

    expect(orderChangesFrom(savedWithoutCommute, draftWithoutCommute)).toEqual([
      { groupId: "school", ids: ["d", "c"] },
    ]);
  });

  it("즐겨찾기를 지우면 그 그룹에서만 빠지고, 저장 전 순서 변경은 남는다", () => {
    const savedWithoutA = groupsWithoutFavorite(saved, "commute", "a");
    const draftWithoutA = groupsWithoutFavorite(draft, "commute", "a");

    expect(savedWithoutA.find((group) => group.id === "commute")?.favorites).toEqual([favorite("b")]);
    expect(savedWithoutA.find((group) => group.id === "school")).toBe(school);
    expect(orderChangesFrom(savedWithoutA, draftWithoutA)).toEqual([
      { groupId: null, ids: ["school", "commute"] },
      { groupId: "school", ids: ["d", "c"] },
    ]);
  });
});

describe("groupsWithOrderChange", () => {
  it("저장된 그룹 순서를 반영하면 그룹 순서 변경은 더 남지 않는다", () => {
    const draft = [withReversedFavorites(school), commute];
    const [groupOrder, ...remaining] = orderChangesFrom(saved, draft);

    const partlySaved = groupsWithOrderChange(saved, groupOrder!);

    expect(partlySaved.map((group) => group.id)).toEqual(["school", "commute"]);
    expect(orderChangesFrom(partlySaved, draft)).toEqual(remaining);
  });

  it("저장된 그룹 안 순서를 반영하면 그 그룹의 변경은 더 남지 않는다", () => {
    const draft = [commute, withReversedFavorites(school)];
    const [favoriteOrder] = orderChangesFrom(saved, draft);

    const allSaved = groupsWithOrderChange(saved, favoriteOrder!);

    expect(allSaved[1]?.favorites.map((item) => item.id)).toEqual(["d", "c"]);
    expect(allSaved[0]).toBe(commute);
    expect(orderChangesFrom(allSaved, draft)).toEqual([]);
  });

  it("받은 목록은 바꾸지 않는다", () => {
    groupsWithOrderChange(saved, { groupId: null, ids: ["school", "commute"] });
    groupsWithOrderChange(saved, { groupId: "school", ids: ["d", "c"] });

    expect(saved).toEqual([commute, school]);
    expect(school.favorites.map((item) => item.id)).toEqual(["c", "d"]);
  });
});

describe("doneLabelOf", () => {
  it("저장하는 중에는 저장 중이라고 알린다", () => {
    expect(doneLabelOf(true, undefined)).toBe("저장 중…");
    expect(doneLabelOf(true, "saveOrder")).toBe("저장 중…");
  });

  it("순서 저장이 실패했으면 다시 저장하기로 바꾼다", () => {
    expect(doneLabelOf(false, "saveOrder")).toBe("다시 저장하기");
  });

  it("삭제처럼 다른 작업이 실패했을 때는 완료 그대로다", () => {
    expect(doneLabelOf(false, "deleteGroup")).toBe("완료");
    expect(doneLabelOf(false, undefined)).toBe("완료");
  });
});
