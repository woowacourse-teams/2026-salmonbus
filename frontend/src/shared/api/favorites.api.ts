import { memberRequest } from "./auth.api";
import type { ApiResult, RequestOptions } from "./client";
import { isFavoriteGroup, isFavoriteGroups, isGroupName, isHomeFavorites } from "./favorites.schema";
import type { FavoriteGroup, FavoriteGroups, FavoriteTarget, HomeFavorites } from "./favorites.types";

const GROUPS = "/api/me/favorite-groups";

async function request<T>(
  url: string,
  options: RequestOptions,
  validate?: (body: unknown) => body is T,
): Promise<ApiResult<T>> {
  const result = await memberRequest<T>(url, { ...options, errorRequestIdOptional: true });
  return !result.ok || validate === undefined || validate(result.body)
    ? result
    : { ok: false, failure: { kind: "malformed", status: result.status, requestId: result.requestId } };
}

export const fetchFavoriteGroups = (signal?: AbortSignal) =>
  request<FavoriteGroups>(GROUPS, signal ? { signal } : {}, isFavoriteGroups);
export const fetchHomeFavorites = (groupId: string | undefined, signal: AbortSignal) =>
  request<HomeFavorites>(
    `/api/me/favorites${groupId === undefined ? "" : `?${new URLSearchParams({ groupId })}`}`,
    { signal },
    isHomeFavorites,
  );
export const createFavoriteGroup = (name: string) =>
  request<FavoriteGroup>(GROUPS, { method: "POST", body: { name: name.trim() } }, isFavoriteGroup);
export const renameFavoriteGroup = (id: string, name: string) =>
  request<{ id: string; name: string }>(
    `${GROUPS}/${encodeURIComponent(id)}`,
    { method: "PATCH", body: { name: name.trim() } },
    isGroupName,
  );
export const deleteFavoriteGroup = (id: string) =>
  request<void>(`${GROUPS}/${encodeURIComponent(id)}`, { method: "DELETE" });
export const saveGroupOrder = (groupIds: string[]) =>
  request<void>(`${GROUPS}/order`, { method: "PUT", body: { groupIds } });
export const saveFavoriteOrder = (groupId: string, favoriteIds: string[]) =>
  request<void>(`${GROUPS}/${encodeURIComponent(groupId)}/favorites/order`, { method: "PUT", body: { favoriteIds } });
export const deleteFavorite = (groupId: string, favoriteId: string) =>
  request<void>(`${GROUPS}/${encodeURIComponent(groupId)}/favorites/${encodeURIComponent(favoriteId)}`, {
    method: "DELETE",
  });
export const saveFavoriteSelections = (target: FavoriteTarget, favoriteGroupIds: string[]) =>
  request<void>("/api/me/favorite-selections", { method: "PUT", body: { ...target, favoriteGroupIds } });
