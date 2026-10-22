import { createContext, useContext } from "react";
import { fetchHomeFavorites } from "@/shared/api/favorites.api";
import type { FavoriteGroup, FavoriteGroups } from "@/shared/api/favorites.types";
import type { PolledResource } from "@/shared/api/polledResource";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import type { AuthState } from "./useAuth";

export interface FavoritesContextValue {
  auth: AuthState;
  memberId: string | null;
  resource: PolledResource<FavoriteGroups>;
  groups: FavoriteGroup[];
  reload: () => void;
  revision: number;
}

export const FavoritesContext = createContext<FavoritesContextValue | null>(null);

export function useFavorites() {
  const value = useContext(FavoritesContext);
  if (value === null) throw new Error("FavoritesProvider 안에서 사용해야 합니다.");
  return value;
}

export function useHomeFavorites(groupId: string | null, revision = 0) {
  const { memberId, revision: groupRevision } = useFavorites();
  return usePolledRequest(
    (_, signal) =>
      memberId === null || groupId === null
        ? Promise.resolve({ ok: false as const, failure: { kind: "aborted" as const } })
        : fetchHomeFavorites(groupId === "all" ? undefined : groupId, signal),
    `${memberId ?? "anonymous"}:${groupId}`,
    `${groupRevision}:${revision}`,
  );
}
