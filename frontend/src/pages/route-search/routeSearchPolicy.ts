import type { RouteSummary } from "@/shared/api/routeForecast.types";

export interface InServiceRoute extends RouteSummary {
  status: "FORECAST_READY";
}

export function isInService(route: RouteSummary): route is InServiceRoute {
  return route.status === "FORECAST_READY";
}

export function searchDigitsFrom(text: string): string {
  return text.replace(/\D/g, "");
}

const routeNumberCollator = new Intl.Collator("ko", { numeric: true });

// 숫자 키패드로만 검색해서, G8110처럼 앞에 붙은 영문은 빼고 8110 자리에 놓는다.
// 하이픈은 남겨야 1500-2를 15002가 아니라 1500 다음으로 비교한다.
function routeNumberOf(route: Pick<RouteSummary, "displayName">): string {
  return route.displayName.replace(/^\D+/, "");
}

export function numberOrderedRoutesFor<T extends Pick<RouteSummary, "displayName">>(routes: readonly T[]): T[] {
  return [...routes].sort(
    (left, right) =>
      routeNumberCollator.compare(routeNumberOf(left), routeNumberOf(right)) ||
      routeNumberCollator.compare(left.displayName, right.displayName),
  );
}

export function matchingRoutesFor<T extends Pick<RouteSummary, "displayName">>(
  routes: readonly T[],
  input: string,
): T[] {
  const digits = searchDigitsFrom(input);
  if (digits === "") return [];
  return routes.filter((route) => searchDigitsFrom(route.displayName).startsWith(digits));
}
