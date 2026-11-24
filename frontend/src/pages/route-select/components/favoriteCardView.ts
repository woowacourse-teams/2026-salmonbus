import type { FavoriteStatus, HomeFavorite } from "@/shared/api/favorites.types";
import type { Direction } from "@/shared/api/routeForecast.types";

export type FavoriteCardView =
  | { kind: "retry" }
  | { kind: "notice"; status: Exclude<FavoriteStatus, "READY">; direction: Direction | null }
  | { kind: "link"; routeName: string; stopName: string; direction: Direction | null };

/** 즐겨찾기 카드를 재시도, 안내, 링크 가운데 무엇으로 그릴지 고른다. 응답에 방면이 없으면 저장한 방면을 쓴다. */
export function favoriteCardViewFor(
  favorite: Pick<HomeFavorite, "status" | "route" | "stop">,
  savedDirection: Direction | null,
): FavoriteCardView {
  if (favorite.status === "ERROR") return { kind: "retry" };
  const direction = favorite.stop.direction ?? savedDirection;
  const routeName = favorite.route.displayName;
  const stopName = favorite.stop.name;
  if (!routeName || !stopName) {
    return {
      kind: "notice",
      status: favorite.status === "READY" ? "ERROR" : favorite.status,
      direction: favorite.status === "UNAVAILABLE" ? null : direction,
    };
  }
  return { kind: "link", routeName, stopName, direction };
}
