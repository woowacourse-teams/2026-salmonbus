import type { FavoriteGroup } from "@/shared/api/favorites.types";
import type { FavoriteOperation } from "@/shared/utils/favoritePolicy";

export interface FavoriteOrderChange {
  groupId: string | null;
  ids: string[];
}

function idsOf(items: { id: string }[]): string[] {
  return items.map((item) => item.id);
}

/** 저장된 순서와 달라진 것만 고른다. 그룹 순서가 먼저, 그다음 그룹 안 순서다. */
export function orderChangesFrom(saved: FavoriteGroup[], draft: FavoriteGroup[]): FavoriteOrderChange[] {
  const changes: FavoriteOrderChange[] = [];
  if (JSON.stringify(idsOf(draft)) !== JSON.stringify(idsOf(saved))) changes.push({ groupId: null, ids: idsOf(draft) });
  for (const group of draft) {
    const before = saved.find((candidate) => candidate.id === group.id);
    if (JSON.stringify(group.favorites) !== JSON.stringify(before?.favorites))
      changes.push({ groupId: group.id, ids: idsOf(group.favorites) });
  }
  return changes;
}

export function groupsWithOrderChange(groups: FavoriteGroup[], change: FavoriteOrderChange): FavoriteGroup[] {
  const byChangedOrder = (left: { id: string }, right: { id: string }) =>
    change.ids.indexOf(left.id) - change.ids.indexOf(right.id);
  return change.groupId === null
    ? [...groups].sort(byChangedOrder)
    : groups.map((group) =>
        group.id === change.groupId ? { ...group, favorites: [...group.favorites].sort(byChangedOrder) } : group,
      );
}

export function groupsWithNewGroup(groups: FavoriteGroup[], created: FavoriteGroup): FavoriteGroup[] {
  return [...groups, created];
}

export function groupsWithoutGroup(groups: FavoriteGroup[], groupId: string): FavoriteGroup[] {
  return groups.filter((group) => group.id !== groupId);
}

export function groupsWithoutFavorite(groups: FavoriteGroup[], groupId: string, favoriteId: string): FavoriteGroup[] {
  return groups.map((group) =>
    group.id === groupId
      ? { ...group, favorites: group.favorites.filter((favorite) => favorite.id !== favoriteId) }
      : group,
  );
}

export function doneLabelOf(busy: boolean, failedOperation: FavoriteOperation | undefined): string {
  return busy ? "저장 중…" : failedOperation === "saveOrder" ? "다시 저장하기" : "완료";
}
