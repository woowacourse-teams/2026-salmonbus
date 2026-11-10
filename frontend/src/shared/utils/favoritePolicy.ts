import type { ApiFailure } from "@/shared/api/client";
import type { FavoriteGroup, FavoriteTarget } from "@/shared/api/favorites.types";
import { authErrorOf } from "./authErrorOf";

export const MAX_FAVORITE_GROUPS = 3;
export const MAX_FAVORITES_PER_GROUP = 2;

export const FAVORITES_LOAD_FAILURE_TITLE = "즐겨찾기를 불러오지 못했어요";

const failureLabels = {
  request: "요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.",
  load: "즐겨찾기를 불러오지 못했어요. 다시 시도해 주세요.",
  refresh: "즐겨찾기를 갱신하지 못했어요.",
  createGroup: "그룹을 만들지 못했어요. 다시 시도해 주세요.",
  renameGroup: "그룹 이름을 저장하지 못했어요. 다시 시도해 주세요.",
  deleteGroup: "그룹을 삭제하지 못했어요. 다시 시도해 주세요.",
  deleteFavorite: "즐겨찾기를 삭제하지 못했어요. 다시 시도해 주세요.",
  saveFavorite: "즐겨찾기를 저장하지 못했어요. 다시 시도해 주세요.",
  saveOrder: "순서를 저장하지 못했어요. 다시 시도해 주세요.",
  saveGroupOrder: "그룹 순서를 저장하지 못했어요. 다시 시도해 주세요.",
};

export type FavoriteOperation = keyof typeof failureLabels;

export function sameFavorite(left: FavoriteTarget, right: FavoriteTarget): boolean {
  return left.routeId === right.routeId && left.stopId === right.stopId && left.direction === right.direction;
}

export function selectedGroups(groups: FavoriteGroup[], target: FavoriteTarget): string[] {
  return groups
    .filter((group) => group.favorites.some((favorite) => sameFavorite(favorite, target)))
    .map((group) => group.id);
}

export function favoriteCountFor(group: FavoriteGroup, target: FavoriteTarget, ids: string[]): number {
  const others = group.favorites.filter((favorite) => !sameFavorite(favorite, target)).length;
  return ids.includes(group.id) ? others + 1 : others;
}

export function exceedsFavoriteLimit(groups: FavoriteGroup[], target: FavoriteTarget, ids: string[]): boolean {
  return groups.some((group) => favoriteCountFor(group, target, ids) > MAX_FAVORITES_PER_GROUP);
}

export function groupNameError(name: string): string | null {
  const length = [...new Intl.Segmenter("ko", { granularity: "grapheme" }).segment(name.trim())].length;
  return length < 1 || length > 10 ? "그룹 이름은 앞뒤 공백을 제외한 1~10자로 입력해 주세요." : null;
}

export function favoriteErrorOf(failure: ApiFailure, operation: FavoriteOperation = "request"): string {
  if (failure.kind === "contract") {
    switch (failure.error.code) {
      case "INVALID_FAVORITE_GROUP_NAME":
        return "그룹 이름은 1~10자로 입력해 주세요.";
      case "FAVORITE_GROUP_NAME_CONFLICT":
        return "이미 사용 중인 그룹 이름이에요.";
      case "FAVORITE_GROUP_LIMIT_EXCEEDED":
        return `그룹은 ${MAX_FAVORITE_GROUPS}개까지 만들 수 있어요.`;
      case "LAST_FAVORITE_GROUP":
        return "대표 그룹은 삭제할 수 없어요.";
      case "FAVORITE_LIMIT_EXCEEDED":
        return `한 그룹에는 즐겨찾기를 ${MAX_FAVORITES_PER_GROUP}개까지 저장할 수 있어요.`;
      case "FAVORITE_GROUP_NOT_FOUND":
        return "그룹을 찾지 못했어요. 목록을 다시 확인해 주세요.";
      case "FAVORITE_NOT_FOUND":
        return "즐겨찾기를 찾지 못했어요. 목록을 다시 확인해 주세요.";
      case "FAVORITE_TARGET_AMBIGUOUS":
        return "같은 정류장에 여러 번 방문하는 노선이에요. 정류장을 다시 확인해 주세요.";
      case "FAVORITE_TARGET_UNAVAILABLE":
        return "현재 노선에서 즐겨찾기로 저장할 수 없는 정류장이에요.";
      case "INVALID_FAVORITE_GROUP_ORDER":
      case "INVALID_FAVORITE_ORDER":
        return "목록이 변경됐어요. 다시 확인한 뒤 순서를 저장해 주세요.";
    }
  }
  if ("status" in failure && failure.status === 404) return "즐겨찾기 기능을 준비 중이에요.";
  return authErrorOf(failure, failureLabels[operation]);
}

export function favoriteStopLabelOf(item: { stop: { name?: string } } | null | undefined, index: number): string {
  if (item?.stop.name) return item.stop.name;
  if (item) return `정류장 정보 없음 (즐겨찾기 ${index + 1})`;
  return `즐겨찾기 ${index + 1}`;
}
