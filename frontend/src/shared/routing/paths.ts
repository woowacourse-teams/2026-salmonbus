import type { FavoriteTarget } from "@/shared/api/favorites.types";

export const paths = {
  routeSelect: "/",
  routeSearch: "/search",
  board: "/routes/:routeId",
  login: "/login",
  mypage: "/mypage",
  nickname: "/mypage/nickname",
  favoriteGroup: "/mypage/groups/:groupId",
  privacy: "/privacy",
  privacyEdition: "/privacy/:effectiveDate",
  whatsNew: "/mypage/whats-new",
} as const;

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}

export function privacyEditionPathFor(effectiveDate: string): string {
  return `/privacy/${encodeURIComponent(effectiveDate)}`;
}

export function favoritePath(target: FavoriteTarget): string {
  return `/routes/${encodeURIComponent(target.routeId)}?${new URLSearchParams({ direction: target.direction, stopId: target.stopId })}`;
}
