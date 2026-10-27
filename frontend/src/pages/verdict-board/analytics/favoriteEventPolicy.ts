import type { EventMap } from "@/shared/analytics/events";
import type { FavoriteGroup, FavoriteTarget } from "@/shared/api/favorites.types";
import { sameFavorite, selectedGroups } from "@/shared/utils/favoritePolicy";

// 이미 담긴 정류장의 그룹만 바꾸는 저장은 새로 담은 것이 아니라서 보내지 않는다.
export function favoriteAddedPropertiesOf(
  groups: FavoriteGroup[],
  target: FavoriteTarget,
  chosenGroupIds: string[],
): EventMap["favorite_added"] | null {
  if (chosenGroupIds.length === 0 || selectedGroups(groups, target).length > 0) return null;
  const savedFavorites = groups.flatMap((group) => group.favorites);
  const savedStopCount = savedFavorites.filter(
    (favorite, index) => savedFavorites.findIndex((other) => sameFavorite(other, favorite)) === index,
  ).length;
  return {
    route_id: target.routeId,
    direction: target.direction,
    stop_id: target.stopId,
    group_count: chosenGroupIds.length,
    favorite_count: savedStopCount + 1,
  };
}
