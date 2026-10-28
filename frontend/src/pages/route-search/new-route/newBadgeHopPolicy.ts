import type { SearchView } from "../displayPolicy";
import { searchDigitsFrom } from "../routeSearchPolicy";

interface VerticalSpan {
  top: number;
  bottom: number;
}

export function newBadgeHopKeyFor(view: SearchView, input: string): string | null {
  if (view.kind !== "all" && view.kind !== "matched") return null;
  if (!view.routes.some((route) => route.isNew)) return null;
  return searchDigitsFrom(input);
}

export function hoppingBadgeIndexFor(badges: readonly VerticalSpan[], visibleArea: VerticalSpan): number | null {
  const visibleIndexes = badges.flatMap((badge, index) =>
    badge.top >= visibleArea.top && badge.bottom <= visibleArea.bottom ? [index] : [],
  );
  return visibleIndexes[Math.floor((visibleIndexes.length - 1) / 2)] ?? null;
}
