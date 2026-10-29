import { describe, expect, it } from "@jest/globals";
import type { FavoriteGroup, FavoriteTarget } from "@/shared/api/favorites.types";
import { favoriteAddedPropertiesOf } from "./favoriteEventPolicy";

const target: FavoriteTarget = { routeId: "204000057", stopId: "203000123", direction: "UP" };
const otherStop: FavoriteTarget = { routeId: "234000050", stopId: "203000456", direction: "DOWN" };

function group(id: string, favorites: FavoriteTarget[]): FavoriteGroup {
  return { id, name: id, favorites: favorites.map((favorite, index) => ({ ...favorite, id: `${id}-${index}` })) };
}

describe("favoriteAddedPropertiesOf", () => {
  it("어느 그룹에도 없던 정류장을 담으면 고른 그룹 수와 담은 뒤 서로 다른 정류장 수를 보낸다", () => {
    const groups = [group("commute", [otherStop]), group("weekend", [])];

    expect(favoriteAddedPropertiesOf(groups, target, ["commute", "weekend"])).toEqual({
      route_id: "204000057",
      direction: "UP",
      stop_id: "203000123",
      group_count: 2,
      favorite_count: 2,
    });
  });

  it("처음 담는 정류장이면 favorite_count가 1이다", () => {
    expect(favoriteAddedPropertiesOf([group("commute", [])], target, ["commute"])?.favorite_count).toBe(1);
  });

  it("같은 정류장이 여러 그룹에 있어도 한 번만 센다", () => {
    const groups = [group("commute", [otherStop]), group("weekend", [otherStop])];

    expect(favoriteAddedPropertiesOf(groups, target, ["commute"])?.favorite_count).toBe(2);
  });

  it("이미 담긴 정류장의 그룹만 바꾸면 보내지 않는다", () => {
    const groups = [group("commute", [target]), group("weekend", [])];

    expect(favoriteAddedPropertiesOf(groups, target, ["weekend"])).toBeNull();
  });

  it("고른 그룹이 없으면 보내지 않는다", () => {
    expect(favoriteAddedPropertiesOf([group("commute", [])], target, [])).toBeNull();
  });
});
