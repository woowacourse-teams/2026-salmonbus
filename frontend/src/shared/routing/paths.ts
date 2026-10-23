import type { FavoriteTarget } from "@/shared/api/favorites.types";

export const paths = {
  routeSelect: "/",
  routeSearch: "/search",
  board: "/routes/:routeId",
  login: "/login",
  mypage: "/mypage",
  nickname: "/mypage/nickname",
  favoriteGroup: "/mypage/groups/:groupId",
} as const;

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}

export function favoritePath(target: FavoriteTarget): string {
  return `/routes/${encodeURIComponent(target.routeId)}?${new URLSearchParams({ direction: target.direction, stopId: target.stopId })}`;
}
