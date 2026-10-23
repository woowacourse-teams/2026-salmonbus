import { memberRequest } from "./auth.api";
import type { ApiResult, RequestOptions } from "./client";
import { favoriteGroupFrom, favoriteGroupsFrom, groupNameFrom, homeFavoritesFrom, emptyFrom } from "./favorites.schema";
import type { FavoriteGroup, FavoriteGroups, FavoriteTarget, HomeFavorites } from "./favorites.types";

const GROUPS = "/api/me/favorite-groups";

async function request<T>(
  url: string,
  options: RequestOptions,
  parse: (body: unknown) => T | null,
): Promise<ApiResult<T>> {
  const result = await memberRequest<unknown>(url, options);
  if (!result.ok) return result;
  const body = parse(result.body);
  return body === null
    ? { ok: false, failure: { kind: "malformed", status: result.status, requestId: result.requestId } }
    : {
        ok: true,
        body,
        status: result.status,
        requestId: result.requestId,
        clock: result.clock,
        lifetime: result.lifetime,
      };
}

export const fetchFavoriteGroups = (signal?: AbortSignal) =>
  request<FavoriteGroups>(GROUPS, signal ? { signal } : {}, favoriteGroupsFrom);
export const fetchHomeFavorites = (groupId: string | undefined, signal: AbortSignal) =>
  request<HomeFavorites>(
    `/api/me/favorites${groupId === undefined ? "" : `?${new URLSearchParams({ groupId })}`}`,
    { signal },
    homeFavoritesFrom,
  );
export const createFavoriteGroup = (name: string) =>
  request<FavoriteGroup>(GROUPS, { method: "POST", body: { name: name.trim() } }, favoriteGroupFrom);
export const renameFavoriteGroup = (id: string, name: string) =>
  request<{ id: string; name: string }>(
    `${GROUPS}/${encodeURIComponent(id)}`,
    { method: "PATCH", body: { name: name.trim() } },
    groupNameFrom,
  );
export const deleteFavoriteGroup = (id: string) =>
  request<void>(`${GROUPS}/${encodeURIComponent(id)}`, { method: "DELETE" }, emptyFrom);
export const saveGroupOrder = (groupIds: string[]) =>
  request<void>(`${GROUPS}/order`, { method: "PUT", body: { groupIds } }, emptyFrom);
export const saveFavoriteOrder = (groupId: string, favoriteIds: string[]) =>
  request<void>(
    `${GROUPS}/${encodeURIComponent(groupId)}/favorites/order`,
    { method: "PUT", body: { favoriteIds } },
    emptyFrom,
  );
export const deleteFavorite = (groupId: string, favoriteId: string) =>
  request<void>(
    `${GROUPS}/${encodeURIComponent(groupId)}/favorites/${encodeURIComponent(favoriteId)}`,
    {
      method: "DELETE",
    },
    emptyFrom,
  );
export const saveFavoriteSelections = (target: FavoriteTarget, favoriteGroupIds: string[]) =>
  request<void>("/api/me/favorite-selections", { method: "PUT", body: { ...target, favoriteGroupIds } }, emptyFrom);
