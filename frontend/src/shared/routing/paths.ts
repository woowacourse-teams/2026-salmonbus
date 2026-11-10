import type { FavoriteTarget } from "@/shared/api/favorites.types";

export const paths = {
  routeSelect: "/",
  routeMap: "/route-map",
  favorites: "/favorites",
  routeSearch: "/search",
  board: "/routes/:routeId",
  login: "/login",
  mypage: "/mypage",
  nickname: "/mypage/nickname",
  favoriteGroups: "/favorites/groups",
  favoriteGroup: "/favorites/groups/:groupId",
  legacyFavoriteGroup: "/mypage/groups/:groupId",
  privacy: "/privacy",
  privacyEdition: "/privacy/:effectiveDate",
  whatsNew: "/mypage/whats-new",
} as const;

export function favoriteGroupPathFor(groupId: string): string {
  return `${paths.favoriteGroups}/${encodeURIComponent(groupId)}`;
}

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}

export function privacyEditionPathFor(effectiveDate: string): string {
  return `/privacy/${encodeURIComponent(effectiveDate)}`;
}

export function favoritePath(target: FavoriteTarget): string {
  return `/routes/${encodeURIComponent(target.routeId)}?${new URLSearchParams({ direction: target.direction, stopId: target.stopId })}`;
}
