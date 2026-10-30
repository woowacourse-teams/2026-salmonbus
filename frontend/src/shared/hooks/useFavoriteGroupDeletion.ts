import { useState, type Dispatch, type SetStateAction } from "react";
import { deleteFavoriteGroup, saveGroupOrder } from "@/shared/api/favorites.api";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { useFavoriteAction } from "./useFavoriteAction";

export function useFavoriteGroupDeletion(
  saved: FavoriteGroup[],
  draft: FavoriteGroup[],
  setSaved: Dispatch<SetStateAction<FavoriteGroup[]>>,
) {
  const action = useFavoriteAction();
  const [partial, setPartial] = useState(false);

  async function removeGroup(id: string) {
    if (action.busy || draft[0]?.id === id) return false;
    let orderSaved = false;
    const ok = await action.run(async () => {
      // 서버는 저장된 첫 그룹을 보호하므로 이전 대표를 지우기 전에 새 순서를 반영한다.
      if (saved[0]?.id === id) {
        const ids = draft.map((group) => group.id);
        const result = await saveGroupOrder(ids);
        if (!result.ok) return result;
        orderSaved = true;
        setSaved((current) => [...current].sort((left, right) => ids.indexOf(left.id) - ids.indexOf(right.id)));
      }
      return deleteFavoriteGroup(id);
    });
    if (!ok && orderSaved) setPartial(true);
    return ok;
  }

  return { ...action, partial, setPartial, removeGroup };
}
