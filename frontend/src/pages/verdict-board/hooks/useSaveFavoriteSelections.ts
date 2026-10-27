import { track } from "@/shared/analytics/track";
import { saveFavoriteSelections } from "@/shared/api/favorites.api";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { favoriteAddedPropertiesOf } from "../analytics/favoriteEventPolicy";

export function useSaveFavoriteSelections(target: FavoriteTarget) {
  const { groups } = useFavorites();
  return async (groupIds: string[]) => {
    const added = favoriteAddedPropertiesOf(groups, target, groupIds);
    const result = await saveFavoriteSelections(target, groupIds);
    // 저장 중에 화면을 떠나도 서버에는 담겼으니, 화면 상태가 아니라 저장 결과로 기록한다.
    if (result.ok && added) track("favorite_added", added);
    return result;
  };
}
