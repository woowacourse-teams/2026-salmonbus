import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { newRouteIdsFor, type FirstSeenTimes } from "./new-route/newRoutePolicy";
import { isInService, matchingRoutesFor, type InServiceRoute } from "./routeSearchPolicy";

export type RoutesState =
  | { status: "loading"; routes?: never; firstSeenTimes?: never; checkedAt?: never }
  | { status: "error"; routes?: never; firstSeenTimes?: never; checkedAt?: never }
  | { status: "ready"; routes: RouteSummary[]; firstSeenTimes: FirstSeenTimes; checkedAt: number };

export interface ListedRoute extends InServiceRoute {
  isNew: boolean;
}

export type SearchView =
  | { kind: "empty"; routes?: never }
  | { kind: "loading"; routes?: never }
  | { kind: "error"; routes?: never }
  | { kind: "matched"; routes: ListedRoute[] }
  | { kind: "unmatched"; routes?: never };

export function searchViewFor(routesState: RoutesState, input: string): SearchView {
  if (routesState.status === "error") return { kind: "error" };
  if (input.trim() === "") return { kind: "empty" };
  if (routesState.status === "loading") return { kind: "loading" };
  const { routes, firstSeenTimes, checkedAt } = routesState;
  const newRouteIds = newRouteIdsFor(firstSeenTimes, checkedAt);
  const matched = matchingRoutesFor(routes.filter(isInService), input).map((route) => ({
    ...route,
    isNew: newRouteIds.has(route.id),
  }));
  return matched.length > 0 ? { kind: "matched", routes: matched } : { kind: "unmatched" };
}

export interface RouteSelection {
  route: ListedRoute;
  position: number;
  listedCount: number;
}

export function submitSelectionFor(view: SearchView): RouteSelection | null {
  if (view.kind !== "matched" || view.routes.length !== 1) return null;
  const route = view.routes[0];
  return route ? { route, position: 0, listedCount: view.routes.length } : null;
}
