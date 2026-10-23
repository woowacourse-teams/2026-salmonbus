import { useReducer, type ReactNode } from "react";
import { fetchFavoriteGroups } from "@/shared/api/favorites.api";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { useAuth } from "@/shared/hooks/useAuth";
import { FavoritesContext } from "@/shared/hooks/useFavorites";

interface FavoritesProviderProps {
  children: ReactNode;
}

export function FavoritesProvider({ children }: FavoritesProviderProps) {
  const { state: auth } = useAuth();
  const memberId = auth.status === "authenticated" ? auth.member.id : null;
  const [revision, reload] = useReducer((value: number) => value + 1, 0);
  const resource = usePolledRequest((_, signal) => fetchFavoriteGroups(signal), memberId, revision);
  const groups = latestSuccessOf(resource)?.body.groups ?? [];
  return <FavoritesContext value={{ auth, memberId, resource, groups, reload, revision }}>{children}</FavoritesContext>;
}
