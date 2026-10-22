import { createContext, useContext, useReducer, type ReactNode } from "react";
import { fetchFavoriteGroups, fetchHomeFavorites } from "@/shared/api/favorites.api";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { useAuth } from "@/shared/hooks/useAuth";

function useGroups() {
  const { state: auth } = useAuth();
  const memberId = auth.status === "authenticated" ? auth.member.id : null;
  const [revision, reload] = useReducer((value: number) => value + 1, 0);
  const resource = usePolledRequest(
    (_, signal) =>
      memberId === null
        ? Promise.resolve({ ok: false as const, failure: { kind: "aborted" as const } })
        : fetchFavoriteGroups(signal),
    memberId ?? "anonymous",
    revision,
  );
  const groups: FavoriteGroup[] = latestSuccessOf(resource)?.body.groups ?? [];
  return { auth, memberId, resource, groups, reload, revision };
}

const FavoritesContext = createContext<ReturnType<typeof useGroups> | null>(null);

export function FavoritesProvider({ children }: { children: ReactNode }) {
  const value = useGroups();
  return <FavoritesContext value={value}>{children}</FavoritesContext>;
}

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
