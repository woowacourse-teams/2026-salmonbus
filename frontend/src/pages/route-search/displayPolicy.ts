import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { listedRoutesFor, type FirstSeenTimes, type ListedRoute } from "@/shared/route-list/newRoutePolicy";
import { matchingRoutesFor } from "./routeSearchPolicy";

export type RoutesState =
  | { status: "loading"; routes?: never; firstSeenTimes?: never; checkedAt?: never }
  | { status: "error"; routes?: never; firstSeenTimes?: never; checkedAt?: never }
  | { status: "ready"; routes: RouteSummary[]; firstSeenTimes: FirstSeenTimes; checkedAt: number };

export type SearchView =
  | { kind: "empty"; routes?: never }
  | { kind: "loading"; routes?: never }
  | { kind: "error"; routes?: never }
  | { kind: "all"; routes: ListedRoute[] }
  | { kind: "matched"; routes: ListedRoute[] }
  | { kind: "unmatched"; routes?: never };

export function searchViewFor(routesState: RoutesState, input: string): SearchView {
  if (routesState.status === "error") return { kind: "error" };
  if (routesState.status === "loading") return { kind: "loading" };
  const { routes, firstSeenTimes, checkedAt } = routesState;
  const listedRoutes = listedRoutesFor(routes, firstSeenTimes, checkedAt);
  if (input.trim() === "") {
    return listedRoutes.length > 0 ? { kind: "all", routes: listedRoutes } : { kind: "empty" };
  }
  const matched = matchingRoutesFor(listedRoutes, input);
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
